package com.icqnew.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icqnew.app.data.local.SessionStore
import com.icqnew.app.data.media.MediaCache
import com.icqnew.app.data.media.VoicePlayer
import com.icqnew.app.data.media.VoiceRecorder
import com.icqnew.app.domain.ChatMessage
import com.icqnew.app.domain.ChatPrefs
import com.icqnew.app.domain.ChatRepository
import com.icqnew.app.domain.GroupInfo
import com.icqnew.app.domain.MessageKind
import com.icqnew.app.domain.ObserveMessagesUseCase
import com.icqnew.app.domain.SendMessageUseCase
import com.icqnew.app.domain.User
import com.icqnew.app.domain.isGroupPeer
import com.icqnew.app.presentation.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(
    private val peerUin: Long,
    private val repo: ChatRepository,
    observeMessages: ObserveMessagesUseCase,
    private val sendMessage: SendMessageUseCase,
    private val media: MediaCache,
    private val player: VoicePlayer,
    private val recorder: VoiceRecorder,
    private val store: SessionStore,
) : ViewModel() {

    private val whileUi = SharingStarted.WhileSubscribed(5_000)
    val isGroup: Boolean = isGroupPeer(peerUin)

    /** My @username (to find the messages that tag me). */
    val myUsername: StateFlow<String> = store.snapshots.map { it.username }.stateIn(viewModelScope, whileUi, "")

    val peer: StateFlow<User?> = repo.observePeer(peerUin).stateIn(viewModelScope, whileUi, null)
    val prefs: StateFlow<ChatPrefs> = repo.observePrefs(peerUin).stateIn(viewModelScope, whileUi, ChatPrefs())
    val group: StateFlow<GroupInfo?> =
        (if (isGroup) repo.observeGroup(peerUin) else flowOf(null)).stateIn(viewModelScope, whileUi, null)

    /** uin -> member, used for sender names in group chats. */
    val members: StateFlow<Map<Long, User>> =
        group.map { g -> g?.members?.associateBy { it.uin } ?: emptyMap() }
            .stateIn(viewModelScope, whileUi, emptyMap())

    val messages: StateFlow<List<ChatMessage>> =
        observeMessages(peerUin).stateIn(viewModelScope, whileUi, emptyList())
    val typing: StateFlow<List<Long>> = repo.observeTyping(peerUin).stateIn(viewModelScope, whileUi, emptyList())
    val voiceState: StateFlow<VoicePlayer.State> = player.state
    val myName: StateFlow<String> = store.snapshots.map { it.name }.stateIn(viewModelScope, whileUi, "")

    /** Chats and contacts a message can be forwarded to. */
    val forwardTargets: StateFlow<List<User>> =
        combine(repo.observeChats(), repo.observeContacts()) { chats, contacts ->
            val list = chats.map { it.peer } + contacts
            list.distinctBy { it.uin }
        }.stateIn(viewModelScope, whileUi, emptyList())

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _replyTo = MutableStateFlow<ChatMessage?>(null)
    val replyTo: StateFlow<ChatMessage?> = _replyTo.asStateFlow()

    private val _editing = MutableStateFlow<ChatMessage?>(null)
    val editing: StateFlow<ChatMessage?> = _editing.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** Long server texts (e.g. the .help list of commands) shown in a dialog. */
    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    fun consumeInfo() {
        _info.value = null
    }

    private var visible = false

    init {
        viewModelScope.launch {
            val d = repo.draft(peerUin)
            if (_input.value.isEmpty() && d.isNotEmpty()) _input.value = d
        }
        viewModelScope.launch {
            messages.collect { list ->
                if (visible && list.isNotEmpty()) repo.markRead(peerUin)
            }
        }
        if (isGroup) viewModelScope.launch { runCatching { repo.refreshGroup(peerUin) } }
        viewModelScope.launch {
            repo.notices.collect { text ->
                if (!visible) return@collect
                if (text.contains('\n')) _info.value = text else _toast.value = text
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun onInput(text: String) {
        _input.value = text
        if (text.isNotEmpty() && _editing.value == null) repo.notifyTyping(peerUin)
    }

    fun appendEmoji(emoji: String) {
        _input.value = _input.value + emoji
    }

    /** Deletes the last character / emoji (backspace key of the emoji keyboard). */
    fun backspace() {
        val t = _input.value
        if (t.isEmpty()) return
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(t)
        val end = it.last()
        val start = it.previous()
        _input.value = if (start == android.icu.text.BreakIterator.DONE) "" else t.substring(0, start) + t.substring(end)
    }

    fun joinChat() {
        if (!isGroup) return
        viewModelScope.launch {
            try {
                repo.joinChat(com.icqnew.app.domain.groupIdOf(peerUin))
            } catch (e: Exception) {
                _toast.value = e.userMessage()
            }
        }
    }

    fun leaveChat(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.leaveGroup(peerUin)
                onDone()
            } catch (e: Exception) {
                _toast.value = e.userMessage()
            }
        }
    }

    fun startReply(m: ChatMessage) {
        _editing.value = null
        _replyTo.value = m
    }

    fun startEdit(m: ChatMessage) {
        _replyTo.value = null
        _editing.value = m
        _input.value = m.text
    }

    fun cancelReplyOrEdit() {
        if (_editing.value != null) _input.value = ""
        _editing.value = null
        _replyTo.value = null
    }

    fun send() {
        val text = _input.value
        if (text.isBlank()) return
        val editing = _editing.value
        if (editing != null) {
            viewModelScope.launch {
                if (repo.editMessage(editing, text)) {
                    _editing.value = null
                    _input.value = ""
                } else _toast.value = "Нет соединения с сервером"
            }
            return
        }
        val reply = _replyTo.value?.serverId
        _input.value = ""
        _replyTo.value = null
        viewModelScope.launch { sendMessage(peerUin, text, reply) }
    }

    fun sendSticker(emoji: String) {
        val reply = _replyTo.value?.serverId
        _replyTo.value = null
        viewModelScope.launch { repo.sendSticker(peerUin, emoji, reply) }
    }

    fun sendFile(file: File, name: String, mime: String, caption: String = "") {
        val kind = if (mime.startsWith("image/") && !mime.contains("gif")) MessageKind.IMAGE else MessageKind.FILE
        val reply = _replyTo.value?.serverId
        _replyTo.value = null
        viewModelScope.launch { repo.sendAttachment(peerUin, kind, file, name, mime, 0, reply, caption) }
    }

    /** Live loudness bars while recording a voice message (newest last). */
    private val _levels = MutableStateFlow<List<Float>>(emptyList())
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()
    private val _recStart = MutableStateFlow(0L)
    val recStart: StateFlow<Long> = _recStart.asStateFlow()

    fun startRecording(): Boolean {
        val ok = recorder.start()
        _recording.value = ok
        if (ok) {
            _recStart.value = System.currentTimeMillis()
            _levels.value = emptyList()
            viewModelScope.launch {
                while (_recording.value) {
                    _levels.value = (_levels.value + recorder.level()).takeLast(40)
                    kotlinx.coroutines.delay(90)
                }
            }
        } else {
            _toast.value = "Не удалось включить микрофон"
        }
        return ok
    }

    /** Sends a round video message recorded by the camera. */
    fun sendVideoNote(file: File, durationMs: Long) {
        val reply = _replyTo.value?.serverId
        _replyTo.value = null
        viewModelScope.launch {
            repo.sendAttachment(peerUin, MessageKind.VIDEO_NOTE, file, file.name, "video/mp4", durationMs, reply)
        }
    }

    /** Calls with this person, shown right in the chat timeline (there is no separate "Calls" tab). */
    val calls: StateFlow<List<com.icqnew.app.domain.CallRecord>> = repo.observeCalls()
        .map { list -> list.filter { it.peer == peerUin } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- auto-delete timer ----
    private val _ttl = MutableStateFlow(0)
    val ttl: StateFlow<Int> = _ttl.asStateFlow()

    fun loadTtl() {
        viewModelScope.launch { _ttl.value = repo.chatTtl(peerUin) }
    }

    fun setTtl(seconds: Int) {
        viewModelScope.launch {
            try {
                repo.setChatTtl(peerUin, seconds)
                _ttl.value = seconds
            } catch (e: Exception) {
                _toast.value = e.message ?: "Не удалось изменить автоудаление"
            }
        }
    }

    suspend fun linkPreview(url: String) = repo.linkPreview(url)

    suspend fun lookupPhone(phone: String) = repo.lookupPhone(phone)

    suspend fun resolveMention(username: String) = repo.resolveMention(username)

    suspend fun streamSource(id: Long): Pair<String, Map<String, String>>? =
        messages.value.firstOrNull { it.id == id }?.let { streamSource(it) }


    fun finishRecording(send: Boolean) {
        if (!_recording.value) return
        _recording.value = false
        if (!send) {
            recorder.cancel()
            return
        }
        val r = recorder.stop()
        if (r == null) {
            _toast.value = "Слишком коротко — удерживайте кнопку микрофона, пока говорите"
            return
        }
        val reply = _replyTo.value?.serverId
        _replyTo.value = null
        viewModelScope.launch {
            repo.sendAttachment(peerUin, MessageKind.VOICE, r.first, r.first.name, "audio/mp4", r.second, reply)
        }
    }

    // ---- message actions ------------------------------------------------------

    fun sendPoll(question: String, options: List<String>, multiple: Boolean, anonymous: Boolean) {
        viewModelScope.launch { repo.sendPoll(peerUin, question, options, multiple, anonymous) }
    }

    fun vote(m: ChatMessage, options: List<Int>) {
        viewModelScope.launch {
            repo.votePoll(m, options).onFailure { _toast.value = it.message ?: "Не удалось проголосовать" }
        }
    }

    private val reportedViews = HashSet<Long>()

    /** Channel posts that were on screen: counted once per opening of the chat. */
    fun reportViews(ids: List<Long>) {
        val fresh = ids.filter { reportedViews.add(it) }
        if (fresh.isEmpty()) return
        viewModelScope.launch { repo.reportViews(fresh) }
    }

    fun react(m: ChatMessage, emoji: String) {
        viewModelScope.launch {
            if (!repo.react(m, emoji)) _toast.value = "Нет соединения с сервером"
        }
    }

    fun delete(m: ChatMessage, forEveryone: Boolean) {
        viewModelScope.launch {
            if (!repo.deleteMessage(m, forEveryone)) _toast.value = "Нет соединения с сервером"
        }
    }

    fun retry(m: ChatMessage) {
        viewModelScope.launch { repo.retry(m) }
    }

    fun senderName(m: ChatMessage): String = when {
        m.fromMe -> myName.value
        isGroup -> members.value[m.fromUin]?.name ?: "Участник"
        else -> peer.value?.name ?: "ID ${m.fromUin}"
    }

    fun forward(m: ChatMessage, targets: List<Long>) {
        if (targets.isEmpty()) return
        val from = senderName(m)
        viewModelScope.launch {
            repo.forward(m, targets, from)
            _toast.value = if (targets.size == 1) "Сообщение переслано" else "Переслано в ${targets.size} чата"
        }
    }

    // ---- chat options ----------------------------------------------------------

    fun setWallpaper(spec: String) {
        viewModelScope.launch { repo.setChatWallpaper(peerUin, spec) }
    }

    fun setMuted(muted: Boolean) {
        viewModelScope.launch { repo.setMuted(peerUin, muted) }
    }

    fun setBlocked(blocked: Boolean) {
        viewModelScope.launch {
            try {
                repo.setBlocked(peerUin, blocked)
            } catch (e: Exception) {
                _toast.value = e.userMessage()
            }
        }
    }

    fun clearHistory(forAll: Boolean) {
        viewModelScope.launch {
            try {
                repo.clearHistory(peerUin, forAll)
                _toast.value = if (forAll) "История очищена у всех" else "История очищена"
            } catch (e: Exception) {
                _toast.value = e.userMessage()
            }
        }
    }

    // ---- media -------------------------------------------------------------------

    /**
     * Voice messages that follow each other are played one after another (like Telegram):
     * when one ends, the next message — if it is a voice message too — starts by itself.
     */
    fun playVoice(m: ChatMessage) {
        // this message + the voice messages right after it: they play one after another,
        // also when I leave the chat (the player lives in the app, mini player on top)
        val list = messages.value.filter { it.kind != MessageKind.SYSTEM }
        val start = list.indexOfFirst { it.id == m.id }.coerceAtLeast(0)
        val run = buildList {
            add(m)
            for (x in list.drop(start + 1)) {
                if (x.kind == MessageKind.VOICE && !x.deleted) add(x) else break
            }
        }
        val chatName = peer.value?.name ?: ""
        player.play(run.map { x ->
            VoicePlayer.Item(
                x.id, x.attId, x.localPath, x.attName ?: "voice.m4a",
                title = when {
                    x.fromMe -> "Вы"
                    peerUin < 0 -> members.value[x.fromUin]?.name ?: chatName
                    else -> chatName
                },
                peer = peerUin,
            )
        }, 0)
    }

    /** Round video watched with sound: the dot goes away for the sender too. */
    fun markPlayed(m: ChatMessage) {
        if (!m.played) viewModelScope.launch { repo.markPlayed(m.id) }
    }

    fun seekVoice(m: ChatMessage, fraction: Float) {
        player.seek(m.id, m.attId, m.localPath, m.attName ?: "voice.m4a", fraction)
    }

    fun voiceSpeed() = player.cycleSpeed()

    suspend fun imageFile(m: ChatMessage): File? {
        m.localPath?.let { p -> File(p).takeIf { it.exists() }?.let { return it } }
        val id = m.attId ?: return null
        return media.file(id, m.attName ?: "image")
    }

    /** Picture of a publication quoted in an answer. */
    suspend fun storyFile(attId: String): File? = runCatching { media.file(attId, "story_$attId.jpg") }.getOrNull()

    suspend fun streamSource(m: ChatMessage): Pair<String, Map<String, String>>? {
        m.localPath?.let { p -> if (File(p).exists()) return ("file://" + p) to emptyMap() }
        return m.attId?.let { media.streamSource(it) }
    }

    fun saveFile(m: ChatMessage, onResult: (Boolean) -> Unit) {
        val id = m.attId ?: return onResult(false)
        viewModelScope.launch { onResult(media.saveToDownloads(id, m.attName ?: "file", m.attMime)) }
    }

    fun onEnter() {
        visible = true
        repo.setActiveChat(peerUin)
        viewModelScope.launch { repo.markRead(peerUin) }
    }

    fun onLeave() {
        visible = false
        repo.setActiveChat(null)
        // voice playback goes on after leaving the chat (mini player at the top)
        finishRecording(false)
        val text = if (_editing.value == null) _input.value else ""
        viewModelScope.launch { repo.saveDraft(peerUin, text) }
    }

    override fun onCleared() {
        repo.setActiveChat(null)
        recorder.cancel()
    }
}
