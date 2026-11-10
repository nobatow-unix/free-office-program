package com.icqnew.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icqnew.app.domain.AuthRepository
import com.icqnew.app.domain.ChatPreview
import com.icqnew.app.domain.ChatRepository
import com.icqnew.app.domain.ConnectionState
import com.icqnew.app.domain.ObserveChatsUseCase
import com.icqnew.app.domain.PrivacySettings
import com.icqnew.app.domain.Session
import com.icqnew.app.domain.SettingsRepository
import com.icqnew.app.domain.SyncPhoneBookUseCase
import com.icqnew.app.domain.User
import com.icqnew.app.domain.ThemeMode
import com.icqnew.app.domain.AppSettings
import com.icqnew.app.domain.CallRecord
import com.icqnew.app.domain.isGroupPeer
import com.icqnew.app.presentation.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val chatRepo: ChatRepository,
    observeChats: ObserveChatsUseCase,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val syncPhoneBook: SyncPhoneBookUseCase,
) : ViewModel() {

    private val whileUi = SharingStarted.WhileSubscribed(5_000)

    /** null = still loading from Room (avoids flashing the empty state). */
    val chats: StateFlow<List<ChatPreview>?> =
        observeChats().stateIn(viewModelScope, whileUi, null)

    val contacts: StateFlow<List<User>> =
        chatRepo.observeContacts().stateIn(viewModelScope, whileUi, emptyList())

    val connection: StateFlow<ConnectionState> = chatRepo.connection

    val session: StateFlow<Session?> = auth.session.stateIn(viewModelScope, whileUi, null)

    val soundOn: StateFlow<Boolean> = settings.soundOn.stateIn(viewModelScope, whileUi, true)

    val privacy: StateFlow<PrivacySettings> =
        settings.privacy.stateIn(viewModelScope, whileUi, PrivacySettings())

    val stories: StateFlow<List<com.icqnew.app.domain.StoryGroup>> = chatRepo.stories

    /** Chats where somebody tagged me (@) — shown with an "@" badge. */
    val mentionPeers: StateFlow<Set<Long>> = chatRepo.observeMentionPeers().stateIn(viewModelScope, whileUi, emptySet())

    // ---- contacts ----
    private val _contactCards = MutableStateFlow<List<com.icqnew.app.domain.ContactCard>?>(null)
    val contactCards: StateFlow<List<com.icqnew.app.domain.ContactCard>?> = _contactCards.asStateFlow()
    private val _incoming = MutableStateFlow<List<com.icqnew.app.domain.ContactCard>>(emptyList())
    val incoming: StateFlow<List<com.icqnew.app.domain.ContactCard>> = _incoming.asStateFlow()

    init {
        viewModelScope.launch { chatRepo.contactEvents.collect { refreshContacts() } }
    }

    fun refreshContacts() {
        viewModelScope.launch {
            runCatching { _contactCards.value = chatRepo.contactCards() }
            runCatching { _incoming.value = chatRepo.incomingContacts() }
        }
    }

    fun addContact(phone: String?, query: String?, name: String?, share: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val c = chatRepo.addContactBy(phone, query, name, share)
                _message.value = "${c.user.name} добавлен(а) в контакты"
                refreshContacts()
                onResult(true)
            } catch (e: Exception) {
                _message.value = e.userMessage()
                onResult(false)
            }
        }
    }

    fun refreshStories() {
        viewModelScope.launch { runCatching { chatRepo.refreshStories() } }
    }

    fun postStory(jpeg: ByteArray, text: String, dir: java.io.File) {
        viewModelScope.launch {
            try {
                val f = java.io.File(dir, "story_${System.currentTimeMillis()}.jpg")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f.writeBytes(jpeg) }
                chatRepo.postStory(f, text)
                _message.value = "Публикация готова"
            } catch (e: Exception) {
                _message.value = "Не удалось опубликовать"
            }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot text for a snackbar (sync result, errors). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /** Matches the phone book with registered users. [silent] = only report when something was found. */
    fun syncContacts(silent: Boolean) {
        viewModelScope.launch {
            try {
                val n = syncPhoneBook()
                if (n > 0) _message.value = "Найдено контактов: $n"
                else if (!silent) _message.value = "Новых контактов не найдено"
            } catch (e: Exception) {
                if (!silent) _message.value = e.userMessage()
            }
        }
    }

    fun setSoundChoice(v: String) {
        viewModelScope.launch { settings.setSoundChoice(v); settings.setSound(v != "none") }
    }

    /** Auto-delete for all chats: -1 = not loaded yet. */
    private val _defaultTtl = MutableStateFlow(-1)
    val defaultTtl: StateFlow<Int> = _defaultTtl.asStateFlow()

    fun loadDefaultTtl() {
        viewModelScope.launch { runCatching { chatRepo.defaultTtl() }.onSuccess { _defaultTtl.value = it } }
    }

    fun setDefaultTtl(sec: Int) {
        val old = _defaultTtl.value
        _defaultTtl.value = sec
        viewModelScope.launch {
            runCatching { chatRepo.setDefaultTtl(sec) }
                .onSuccess { _defaultTtl.value = it }
                .onFailure { _defaultTtl.value = old }
        }
    }

    fun setSwipeAction(v: String) {
        viewModelScope.launch { settings.setSwipeAction(v) }
    }

    fun setFolders(list: List<com.icqnew.app.domain.ChatFolder>) {
        viewModelScope.launch { settings.setFolders(list) }
    }

    fun setLanguage(code: String) {
        viewModelScope.launch { settings.setLanguage(code) }
    }

    fun setArchived(peer: Long, archived: Boolean) {
        viewModelScope.launch {
            settings.setArchived(peer, archived)
            _message.value = if (archived) "Чат перенесён в архив" else "Чат возвращён из архива"
        }
    }

    fun setSound(on: Boolean) {
        viewModelScope.launch { settings.setSound(on) }
    }

    fun setPrivacy(p: PrivacySettings) {
        viewModelScope.launch {
            try {
                settings.setPrivacy(p)
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    val theme: StateFlow<ThemeMode> = settings.theme.stateIn(viewModelScope, whileUi, ThemeMode.SYSTEM)

    val app: StateFlow<AppSettings> = settings.app.stateIn(viewModelScope, whileUi, AppSettings())

    val typingPeers: StateFlow<Set<Long>> = chatRepo.observeTypingPeers().stateIn(viewModelScope, whileUi, emptySet())

    private val _sessions = MutableStateFlow<List<com.icqnew.app.domain.DeviceSession>>(emptyList())
    val sessions: StateFlow<List<com.icqnew.app.domain.DeviceSession>> = _sessions.asStateFlow()

    fun loadSessions() {
        viewModelScope.launch {
            try {
                _sessions.value = auth.sessions()
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun endSession(id: String) {
        viewModelScope.launch {
            try {
                auth.endSession(id)
                _sessions.value = auth.sessions()
                _message.value = if (id == "others") "Остальные устройства отключены" else "Устройство отключено"
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun setUsername(u: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                auth.setUsername(u)
                _message.value = if (u.isBlank()) "Имя пользователя удалено" else "Сохранено: @" + u.trim().removePrefix("@")
                onDone()
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    suspend fun checkUsername(u: String): String? = try {
        auth.checkUsername(u)
    } catch (e: Exception) {
        e.userMessage()
    }

    fun openSaved(onReady: (Long) -> Unit) {
        viewModelScope.launch { onReady(chatRepo.openSaved()) }
    }

    val calls: StateFlow<List<CallRecord>> = chatRepo.observeCalls().stateIn(viewModelScope, whileUi, emptyList())

    val blocked: StateFlow<List<User>> = chatRepo.observeBlocked().stateIn(viewModelScope, whileUi, emptyList())

    fun setWallpaper(spec: String) {
        viewModelScope.launch { settings.setWallpaper(spec) }
    }

    fun setAccent(i: Int) {
        viewModelScope.launch { settings.setAccent(i) }
    }

    fun setFontScale(f: Float) {
        viewModelScope.launch { settings.setFontScale(f) }
    }

    fun setVibrate(on: Boolean) {
        viewModelScope.launch { settings.setVibrate(on) }
    }

    fun setNotifyPreview(on: Boolean) {
        viewModelScope.launch { settings.setNotifyPreview(on) }
    }

    fun unblock(uin: Long) {
        viewModelScope.launch {
            try {
                chatRepo.setBlocked(uin, false)
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun clearCalls() {
        viewModelScope.launch { chatRepo.clearCalls() }
    }

    fun clearCache(dir: java.io.File) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { f -> f.length().also { f.delete() } }
            _message.value = "Очищено: " + com.icqnew.app.data.local.FileUtils.humanSize(freed)
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setTheme(mode) }
    }

    // ---- my full profile (birthday, colour, channel) ----
    private val _myProfile = MutableStateFlow<User?>(null)
    val myProfile: StateFlow<User?> = _myProfile.asStateFlow()

    fun loadMyProfile(uin: Long) {
        viewModelScope.launch {
            chatRepo.fetchUserFresh(uin)?.let { _myProfile.value = it }
        }
    }

    /** «Изменить»: everything of the account in one go (name, surname, about, email, birthday, colour, channel, @name). */
    fun saveAccount(
        name: String, surname: String, about: String, email: String, birthday: String, color: Int, channelId: Long,
        username: String, onDone: () -> Unit,
    ) {
        if (name.isBlank()) { _message.value = "Введите имя"; return }
        viewModelScope.launch {
            try {
                val un = username.trim().removePrefix("@")
                if (un != session.value?.username.orEmpty()) auth.setUsername(un)
                auth.updateProfile(name.trim(), about.trim())
                _myProfile.value = chatRepo.updateProfileExtra(name.trim(), birthday.trim(), channelId, color, surname.trim(), email.trim())
                _message.value = "Профиль сохранён"
                onDone()
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    // ---- Premium ----
    private val _premium = MutableStateFlow<com.icqnew.app.data.remote.PremiumInfoDto?>(null)
    val premium: StateFlow<com.icqnew.app.data.remote.PremiumInfoDto?> = _premium.asStateFlow()

    fun loadPremium() {
        viewModelScope.launch { runCatching { chatRepo.premiumInfo() }.onSuccess { _premium.value = it }.onFailure { _message.value = it.userMessage() } }
    }

    fun buyPremium(months: Int, group: Long? = null, to: Long? = null) {
        viewModelScope.launch {
            try {
                chatRepo.buyPremium(months, group, to)
                _message.value = when {
                    group != null -> "Premium для чата подключён ⭐"
                    to != null -> "Premium подарен 🎁"
                    else -> "ICQ Premium подключён ⭐"
                }
                loadPremium()
                session.value?.uin?.let { loadMyProfile(it) }
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun sendGift(to: Long, gift: String, text: String, anonymous: Boolean) {
        viewModelScope.launch {
            try {
                chatRepo.sendGift(to, gift, text, anonymous)
                _message.value = "Подарок отправлен 🎁"
                loadPremium()
                session.value?.uin?.let { if (it == to) loadGifts(it) }
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    private val _gifts = MutableStateFlow<List<com.icqnew.app.data.remote.UserGiftDto>>(emptyList())
    val gifts: StateFlow<List<com.icqnew.app.data.remote.UserGiftDto>> = _gifts.asStateFlow()

    fun loadGifts(uin: Long) {
        viewModelScope.launch { runCatching { chatRepo.userGifts(uin) }.onSuccess { _gifts.value = it } }
    }

    fun saveProfile(name: String, about: String, birthday: String, color: Int, channelId: Long) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                auth.updateProfile(name.trim(), about.trim())
                _myProfile.value = chatRepo.updateProfileExtra(name.trim(), birthday.trim(), channelId, color)
                _message.value = "Профиль сохранён"
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun approveWebLogin(qr: String) {
        viewModelScope.launch {
            try {
                chatRepo.approveWebLogin(qr)
                _message.value = "Готово: веб-версия открыта на компьютере"
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun updateProfile(name: String, about: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                auth.updateProfile(name.trim(), about.trim())
                _message.value = "Профиль сохранён"
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun setPinned(peer: Long, v: Boolean) {
        if (v) {
            // 5 pinned chats, 10 with ICQ Premium
            val limit = if (_myProfile.value?.premium == true) 10 else 5
            val pinned = chats.value.orEmpty().count { it.pinned && it.peer.uin != peer }
            if (pinned >= limit) {
                _message.value = if (limit == 5) "Закрепить больше 5 чатов можно с ICQ Premium ⭐" else "Максимум 10 закреплённых чатов"
                return
            }
        }
        viewModelScope.launch { chatRepo.setPinned(peer, v) }
    }

    fun setMuted(peer: Long, v: Boolean) {
        viewModelScope.launch { chatRepo.setMuted(peer, v) }
    }

    fun markRead(peer: Long) {
        viewModelScope.launch { chatRepo.markRead(peer) }
    }

    fun deleteChat(peer: Long, forAll: Boolean = false) {
        viewModelScope.launch {
            try {
                if (isGroupPeer(peer)) chatRepo.leaveGroup(peer) else chatRepo.deleteChat(peer, forAll)
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun uploadAvatar(jpeg: ByteArray) {
        viewModelScope.launch {
            try {
                auth.uploadAvatar(jpeg)
                _message.value = "Фото обновлено"
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            onDone()
        }
    }

    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                auth.deleteAccount()
                onDone()
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }
}
