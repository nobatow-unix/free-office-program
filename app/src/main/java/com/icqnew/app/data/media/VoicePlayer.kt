package com.icqnew.app.data.media

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Plays one voice message at a time and exposes the progress. */
class VoicePlayer(private val cache: MediaCache, private val scope: CoroutineScope) {

    data class State(
        val playingId: Long? = null,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val paused: Boolean = false,
        val speed: Float = 1f,
    )

    /** Pending seek (0..1) for a message that is not loaded yet. */
    private var pendingSeek: Float? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Tap on the play button: starts this message, or stops it if it is already playing. */
    fun toggle(messageId: Long, attId: String?, localPath: String?, name: String) {
        if (_state.value.playingId == messageId) {
            // same message: pause / continue (not from the start)
            val p = player ?: return
            try {
                if (p.isPlaying) {
                    p.pause()
                    _state.value = _state.value.copy(paused = true)
                } else {
                    p.start()
                    _state.value = _state.value.copy(paused = false)
                }
            } catch (e: IllegalStateException) {
                stop()
            }
            return
        }
        stop()
        scope.launch(Dispatchers.Main) {
            val local = localPath?.let { File(it) }?.takeIf { it.exists() }
            val f = local ?: attId?.let { cache.file(it, name) } ?: return@launch
            val p = MediaPlayer()
            try {
                p.setDataSource(f.absolutePath)
                p.prepare()
                p.setOnCompletionListener { stop() }
                applySpeed(p, _state.value.speed)
                pendingSeek?.let { f -> p.seekTo((p.duration * f).toInt().coerceIn(0, p.duration)) }
                pendingSeek = null
                p.start()
                player = p
                _state.value = State(messageId, p.currentPosition, p.duration, speed = _state.value.speed)
                ticker = scope.launch(Dispatchers.Main) {
                    while (isActive) {
                        delay(200)
                        val pos = player?.currentPosition ?: 0
                        _state.value = _state.value.copy(positionMs = pos)
                    }
                }
            } catch (e: Exception) {
                p.release()
            }
        }
    }

    /** Drag / tap on the waveform: jump to [fraction] of the message (starts it if needed). */
    fun seek(messageId: Long, attId: String?, localPath: String?, name: String, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        val p = player
        if (_state.value.playingId == messageId && p != null) {
            try {
                p.seekTo((p.duration * f).toInt())
                _state.value = _state.value.copy(positionMs = p.currentPosition)
            } catch (e: IllegalStateException) {
                // ignore
            }
        } else {
            pendingSeek = f
            toggle(messageId, attId, localPath, name)
        }
    }

    /** 1x → 1.5x → 2x → 1x */
    fun cycleSpeed() {
        val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.let { applySpeed(it, next) }
        _state.value = _state.value.copy(speed = next)
    }

    private fun applySpeed(p: MediaPlayer, speed: Float) {
        try {
            val wasPlaying = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(speed)
            if (!wasPlaying) p.pause()
        } catch (e: Exception) {
            // some devices cannot change the speed
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // already stopped
            }
            it.release()
        }
        player = null
        _state.value = State(speed = _state.value.speed)
    }
}
