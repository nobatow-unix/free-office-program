package com.icqnew.app.data.call

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import com.icqnew.app.data.local.CallEntity
import com.icqnew.app.data.local.IcqDatabase
import com.icqnew.app.data.notify.AppForeground
import com.icqnew.app.data.notify.Notifier
import com.icqnew.app.data.remote.ChatSocket
import com.icqnew.app.data.remote.IcqApi
import com.icqnew.app.data.remote.WsIn
import com.icqnew.app.data.remote.WsOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID

enum class CallPhase { OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }

data class CallUi(
    val callId: String,
    val peer: Long,
    val name: String,
    val avatarUrl: String?,
    val video: Boolean,
    val incoming: Boolean,
    val phase: CallPhase,
    val startedAt: Long = 0,
    val micOff: Boolean = false,
    val speaker: Boolean = false,
    val cameraOff: Boolean = false,
    val remoteVideo: Boolean = false,
    val endText: String? = null,
)

/**
 * One-to-one audio / video calls over WebRTC. Signalling goes through the chat WebSocket
 * ({"type":"call","to":uin,"data":{kind: offer/answer/ice/hangup/decline/busy, ...}}).
 */
class CallManager(
    private val context: Context,
    private val socket: ChatSocket,
    private val api: IcqApi,
    private val db: IcqDatabase,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CallUi?>(null)
    val state: StateFlow<CallUi?> = _state.asStateFlow()

    val eglBase: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localSink: VideoSink? = null
    private var remoteSink: VideoSink? = null
    private var pendingOffer: String? = null
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private var timeoutJob: Job? = null
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var toneJob: Job? = null

    init {
        scope.launch {
            socket.events.collect { ev -> if (ev.type == "call") runCatching { onSignal(ev) } }
        }
    }

    // ---- public API --------------------------------------------------------------

    fun startCall(peer: Long, name: String, avatarUrl: String?, video: Boolean) {
        if (_state.value != null) return
        val id = UUID.randomUUID().toString()
        _state.value = CallUi(id, peer, name, avatarUrl, video, incoming = false, phase = CallPhase.OUTGOING,
            speaker = video)
        startRingback()
        scope.launch {
            try {
                setupPeer(video)
                val offer = createSdp(offer = true)
                send(peer, buildJsonObject {
                    put("kind", "offer"); put("call_id", id); put("sdp", offer); put("video", video)
                })
            } catch (e: Exception) {
                finish("Не удалось начать звонок", "CANCELLED", notifyPeer = false)
            }
        }
        armTimeout(45_000) { finish("Нет ответа", "CANCELLED") }
    }

    fun accept() {
        val s = _state.value ?: return
        if (s.phase != CallPhase.INCOMING) return
        stopRinging()
        notifier.cancelCall()
        _state.update { it?.copy(phase = CallPhase.CONNECTING) }
        scope.launch {
            try {
                setupPeer(s.video)
                val offer = pendingOffer ?: return@launch finish("Ошибка звонка", "MISSED")
                setRemote(SessionDescription.Type.OFFER, offer)
                val answer = createSdp(offer = false)
                send(s.peer, buildJsonObject { put("kind", "answer"); put("call_id", s.callId); put("sdp", answer) })
            } catch (e: Exception) {
                finish("Ошибка звонка", "MISSED")
            }
        }
        armTimeout(30_000) { if (_state.value?.phase != CallPhase.ACTIVE) finish("Не удалось соединиться", "MISSED") }
    }

    fun decline() {
        val s = _state.value ?: return
        send(s.peer, buildJsonObject { put("kind", "decline"); put("call_id", s.callId) })
        scope.launch { finish("Отклонён", "DECLINED", notifyPeer = false) }
    }

    fun hangup() {
        val s = _state.value ?: return
        if (s.phase == CallPhase.INCOMING) return decline()
        scope.launch { finish("Звонок завершён", if (s.phase == CallPhase.ACTIVE) "DONE" else "CANCELLED") }
    }

    fun toggleMic() {
        val off = !(_state.value?.micOff ?: false)
        audioTrack?.setEnabled(!off)
        _state.update { it?.copy(micOff = off) }
    }

    fun toggleSpeaker() {
        val on = !(_state.value?.speaker ?: false)
        audio().isSpeakerphoneOn = on
        _state.update { it?.copy(speaker = on) }
    }

    fun toggleCamera() {
        val off = !(_state.value?.cameraOff ?: false)
        videoTrack?.setEnabled(!off)
        try {
            if (off) capturer?.stopCapture() else capturer?.startCapture(1280, 720, 30)
        } catch (e: Exception) {
            // ignore
        }
        _state.update { it?.copy(cameraOff = off) }
    }

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    fun attachLocal(sink: VideoSink?) {
        localSink?.let { videoTrack?.removeSink(it) }
        localSink = sink
        sink?.let { videoTrack?.addSink(it) }
    }

    fun attachRemote(sink: VideoSink?) {
        remoteSink?.let { remoteVideoTrack?.removeSink(it) }
        remoteSink = sink
        sink?.let { remoteVideoTrack?.addSink(it) }
    }

    /** Hides the "call ended" screen. */
    fun dismiss() {
        if (_state.value?.phase == CallPhase.ENDED) _state.value = null
    }

    // ---- signalling --------------------------------------------------------------

    private fun send(peer: Long, data: JsonObject) {
        scope.launch { socket.send(WsOut("call", to = peer, data = data)) }
    }

    private fun str(d: JsonObject, k: String): String? = d[k]?.jsonPrimitive?.contentOrNull

    private suspend fun onSignal(ev: WsIn) {
        val from = ev.from ?: return
        val d = ev.data ?: return
        val kind = str(d, "kind") ?: return
        val callId = str(d, "call_id") ?: ""
        val cur = _state.value
        when (kind) {
            "offer" -> {
                if (cur != null && cur.phase != CallPhase.ENDED) {
                    send(from, buildJsonObject { put("kind", "busy"); put("call_id", callId) })
                    logCall(from, outgoing = false, video = d["video"]?.jsonPrimitive?.booleanOrNull ?: false, 0, "MISSED")
                    return
                }
                val video = d["video"]?.jsonPrimitive?.booleanOrNull ?: false
                pendingOffer = str(d, "sdp")
                pendingIce.clear()
                remoteSet = false
                val name = ev.user?.name ?: db.contactDao().get(from)?.name ?: "ID $from"
                val avatar = ev.user?.avatarUrl ?: db.contactDao().get(from)?.avatarUrl
                _state.value = CallUi(callId, from, name, avatar, video, incoming = true, phase = CallPhase.INCOMING)
                startRinging()
                if (!AppForeground.isForeground()) notifier.showIncomingCall(name, from, video)
                armTimeout(45_000) { finish("Пропущенный звонок", "MISSED", notifyPeer = false, missedNotice = true) }
            }
            "answer" -> {
                if (cur == null || cur.callId != callId || cur.incoming) return
                stopRinging()
                _state.update { it?.copy(phase = CallPhase.CONNECTING) }
                setRemote(SessionDescription.Type.ANSWER, str(d, "sdp") ?: return)
                armTimeout(30_000) { if (_state.value?.phase != CallPhase.ACTIVE) finish("Не удалось соединиться", "CANCELLED") }
            }
            "ice" -> {
                if (cur != null && cur.callId != callId) return
                val cand = IceCandidate(
                    str(d, "mid") ?: "", d["index"]?.jsonPrimitive?.intOrNull ?: 0, str(d, "candidate") ?: return,
                )
                val p = pc
                if (p != null && remoteSet) p.addIceCandidate(cand) else pendingIce.add(cand)
            }
            "hangup", "decline", "busy" -> {
                if (cur == null || cur.callId != callId) return
                val text = when (kind) {
                    "busy" -> "Абонент занят"
                    "decline" -> "Звонок отклонён"
                    else -> "Звонок завершён"
                }
                val status = when {
                    cur.phase == CallPhase.ACTIVE -> "DONE"
                    cur.incoming -> "MISSED"
                    kind == "hangup" -> "CANCELLED"
                    else -> "DECLINED"
                }
                finish(text, status, notifyPeer = false, missedNotice = cur.incoming && cur.phase == CallPhase.INCOMING)
            }
        }
    }

    // ---- WebRTC --------------------------------------------------------------------

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule())
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val fallback = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        return try {
            api.iceServers().map { el ->
                val o = el.jsonObject
                val urls = o["urls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                PeerConnection.IceServer.builder(urls)
                    .setUsername(o["username"]?.jsonPrimitive?.contentOrNull ?: "")
                    .setPassword(o["credential"]?.jsonPrimitive?.contentOrNull ?: "")
                    .createIceServer()
            }.ifEmpty { fallback }
        } catch (e: Exception) {
            fallback
        }
    }

    private suspend fun setupPeer(video: Boolean) {
        val f = ensureFactory()
        val am = audio()
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = video
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peerConnection = f.createPeerConnection(config, observer) ?: error("no peer connection")
        pc = peerConnection
        val aSource = f.createAudioSource(MediaConstraints())
        val aTrack = f.createAudioTrack("icq-audio", aSource)
        audioTrack = aTrack
        peerConnection.addTrack(aTrack, listOf("icq"))
        if (video) {
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (name != null) {
                val cap = enumerator.createCapturer(name, null)
                val helper = SurfaceTextureHelper.create("icq-capture", eglBase.eglBaseContext)
                val source = f.createVideoSource(false)
                cap.initialize(helper, context, source.capturerObserver)
                cap.startCapture(1280, 720, 30)
                val track = f.createVideoTrack("icq-video", source)
                localSink?.let { track.addSink(it) }
                capturer = cap
                textureHelper = helper
                videoSource = source
                videoTrack = track
                peerConnection.addTrack(track, listOf("icq"))
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                    stopRinging()
                    timeoutJob?.cancel()
                    _state.update {
                        if (it == null || it.phase == CallPhase.ACTIVE) it
                        else it.copy(phase = CallPhase.ACTIVE, startedAt = System.currentTimeMillis())
                    }
                }
                PeerConnection.IceConnectionState.FAILED ->
                    scope.launch { finish("Соединение потеряно", "DONE") }
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
            val c = candidate ?: return
            val s = _state.value ?: return
            send(s.peer, buildJsonObject {
                put("kind", "ice"); put("call_id", s.callId)
                put("mid", c.sdpMid); put("index", c.sdpMLineIndex); put("candidate", c.sdp)
            })
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track()
            if (track is VideoTrack) {
                remoteVideoTrack = track
                remoteSink?.let { track.addSink(it) }
                _state.update { it?.copy(remoteVideo = true) }
            }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private suspend fun createSdp(offer: Boolean): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val p = pc ?: return@suspendCancellableCoroutine cont.resumeWith(Result.failure(IllegalStateException()))
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (_state.value?.video == true) "true" else "false"))
        }
        val obs = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) {
                    cont.resumeWith(Result.failure(IllegalStateException("no sdp")))
                    return
                }
                p.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(d: SessionDescription?) {}
                    override fun onSetSuccess() {
                        if (cont.isActive) cont.resumeWith(Result.success(desc.description))
                    }
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetFailure(e: String?) {
                        if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e)))
                    }
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) {
                if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e)))
            }
            override fun onSetFailure(e: String?) {}
        }
        if (offer) p.createOffer(obs, constraints) else p.createAnswer(obs, constraints)
    }

    private suspend fun setRemote(type: SessionDescription.Type, sdp: String) {
        val p = pc ?: return
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            p.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription?) {}
                override fun onSetSuccess() {
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) {
                    if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(e)))
                }
            }, SessionDescription(type, sdp))
        }
        remoteSet = true
        for (c in pendingIce) p.addIceCandidate(c)
        pendingIce.clear()
    }

    // ---- end of call ---------------------------------------------------------------

    private fun armTimeout(ms: Long, action: suspend () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(ms)
            action()
        }
    }

    private suspend fun finish(text: String, status: String, notifyPeer: Boolean = true, missedNotice: Boolean = false) {
        val s = _state.value ?: return
        if (s.phase == CallPhase.ENDED) return
        if (notifyPeer) send(s.peer, buildJsonObject { put("kind", "hangup"); put("call_id", s.callId) })
        timeoutJob?.cancel()
        stopRinging()
        notifier.cancelCall()
        val duration = if (s.startedAt > 0) ((System.currentTimeMillis() - s.startedAt) / 1000).toInt() else 0
        logCall(s.peer, outgoing = !s.incoming, video = s.video, duration, status)
        if (missedNotice) notifier.showMissedCall(s.name, s.peer)
        release()
        _state.value = s.copy(phase = CallPhase.ENDED, endText = text)
        delay(1_500)
        if (_state.value?.callId == s.callId && _state.value?.phase == CallPhase.ENDED) _state.value = null
    }

    private suspend fun logCall(peer: Long, outgoing: Boolean, video: Boolean, duration: Int, status: String) {
        runCatching {
            db.callDao().insert(CallEntity(peer = peer, outgoing = outgoing, video = video,
                ts = System.currentTimeMillis(), duration = duration, status = status))
        }
    }

    private fun release() {
        try {
            capturer?.stopCapture()
        } catch (e: Exception) {
            // ignore
        }
        capturer?.dispose()
        capturer = null
        videoSource?.dispose()
        videoSource = null
        textureHelper?.dispose()
        textureHelper = null
        videoTrack = null
        remoteVideoTrack = null
        audioTrack = null
        pc?.dispose()
        pc = null
        pendingOffer = null
        pendingIce.clear()
        remoteSet = false
        val am = audio()
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun audio(): AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.also { it.play() }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startRingback() {
        toneJob?.cancel()
        toneJob = scope.launch {
            try {
                val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
                tone = t
                while (true) {
                    t.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000)
                    delay(3_000)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        toneJob?.cancel()
        toneJob = null
        tone?.release()
        tone = null
    }
}
