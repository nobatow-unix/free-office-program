package com.icqnew.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// ---- Repository contracts (implemented in the data layer) -----------------

interface AuthRepository {
    val session: Flow<Session?>
    /** true = the code was sent to the «ICQ» chat on the user's other device */
    suspend fun requestCode(phone: String): Boolean
    suspend fun verify(phone: String, code: String, name: String?): VerifyResult
    /** true = the code was also sent to the «ICQ» chat; false = only by e-mail */
    suspend fun requestEmailCode(email: String): Boolean
    suspend fun verifyEmail(email: String, code: String, name: String?): VerifyResult
    /** login = ID, @username or e-mail */
    suspend fun passwordLogin(login: String, password: String): VerifyResult
    /** QR sign-in on this phone: (code, text inside the QR) */
    suspend fun qrStart(): Pair<String, String>
    /** null while nobody approved the QR yet */
    suspend fun qrPoll(code: String): VerifyResult?
    suspend fun setPassword(password: String, old: String?)
    suspend fun updateProfile(name: String, about: String)
    suspend fun setUsername(username: String)
    /** Returns null if free, otherwise the reason. */
    suspend fun checkUsername(username: String): String?
    suspend fun sessions(): List<DeviceSession>
    suspend fun endSession(id: String)
    suspend fun uploadAvatar(jpeg: ByteArray)
    suspend fun logout()
    suspend fun deleteAccount()
}

interface SettingsRepository {
    val soundOn: Flow<Boolean>
    val privacy: Flow<PrivacySettings>
    val theme: Flow<ThemeMode>
    val app: Flow<AppSettings>
    suspend fun setSound(on: Boolean)
    suspend fun setTheme(mode: ThemeMode)
    suspend fun setAccent(index: Int)
    suspend fun setFontScale(scale: Float)
    suspend fun setVibrate(on: Boolean)
    suspend fun setNotifyPreview(on: Boolean)
    suspend fun setWallpaper(spec: String)
    suspend fun setSoundChoice(v: String)
    suspend fun setSwipeAction(v: String)
    suspend fun setArchived(peer: Long, archived: Boolean)
    suspend fun setFolders(list: List<ChatFolder>)
    suspend fun setLanguage(code: String)
    /** Saves local options and pushes the server-side ones (searchable / show online). */
    suspend fun setPrivacy(p: PrivacySettings)
}

/** Reads the phone book (only the last 8 digits of every number are ever sent to the server). */
interface PhoneBook {
    suspend fun readKeys(): List<String>
}

interface ChatRepository {
    val connection: StateFlow<ConnectionState>
    fun observeChats(): Flow<List<ChatPreview>>
    fun observeContacts(): Flow<List<User>>
    fun observePeer(uin: Long): Flow<User?>
    fun observePrefs(peer: Long): Flow<ChatPrefs>
    fun observeMessages(peer: Long): Flow<List<ChatMessage>>
    /** UIN of whoever is typing in this chat right now (null = nobody). */
    fun observeTyping(peer: Long): Flow<List<Long>>
    /** Chats where somebody is typing right now (for the chat list). */
    fun observeTypingPeers(): Flow<Set<Long>>
    fun notifyTyping(peer: Long)

    suspend fun sendMessage(peer: Long, text: String, replyTo: Long? = null)
    suspend fun sendSticker(peer: Long, emoji: String, replyTo: Long? = null)
    suspend fun sendAttachment(
        peer: Long, kind: MessageKind, file: File, name: String, mime: String, durMs: Long, replyTo: Long? = null,
        caption: String = "",
    )
    suspend fun forward(message: ChatMessage, targets: List<Long>, originalSender: String)
    /** false = no connection right now. */
    suspend fun editMessage(message: ChatMessage, text: String): Boolean
    suspend fun deleteMessage(message: ChatMessage, forEveryone: Boolean): Boolean
    suspend fun react(message: ChatMessage, emoji: String): Boolean
    suspend fun retry(message: ChatMessage)
    suspend fun markRead(peer: Long)
    /** The chat that is on screen right now (no unread badge / notification for it). */
    fun setActiveChat(uin: Long?)

    suspend fun setPinned(peer: Long, pinned: Boolean)
    suspend fun setMuted(peer: Long, muted: Boolean)
    suspend fun setBlocked(uin: Long, blocked: Boolean)
    /** [forAll]: private chat — also for the other person; group — for every member (creator only). */
    suspend fun clearHistory(peer: Long, forAll: Boolean = false)
    suspend fun deleteChat(peer: Long, forAll: Boolean = false)
    suspend fun draft(peer: Long): String
    suspend fun saveDraft(peer: Long, text: String)

    suspend fun searchUsers(query: String): List<User>
    suspend fun addContact(uin: Long)

    /** My contact list with arrows (who added whom), phones and my own names for people. */
    suspend fun contactCards(): List<ContactCard>
    /** People who added me but I have not added back. */
    suspend fun incomingContacts(): List<ContactCard>
    /** Adds by phone number, or by ID / @username; [name] is my own name for the person. */
    suspend fun addContactBy(phone: String?, query: String?, name: String?, sharePhone: Boolean): ContactCard
    suspend fun removeContact(uin: Long)
    /** Full profile card (phone, arrows) of one person. */
    suspend fun contactCard(uin: Long): ContactCard?
    /** Fires when somebody adds me to his contacts. */
    val contactEvents: kotlinx.coroutines.flow.SharedFlow<ContactCard>
    /** Phone dial code for the login screen ("+993"), by IP or SIM country. */
    suspend fun detectDialCode(sim: String): String
    suspend fun reactStory(id: Long, emoji: String)
    suspend fun storyComments(id: Long): List<StoryComment>
    suspend fun addStoryComment(id: Long, text: String): StoryComment
    suspend fun deleteStoryComment(id: Long, commentId: Long)

    /** Birthday ("DD.MM[.YYYY]", "" clears), my channel (0 clears), profile colour. Null = unchanged. */
    suspend fun updateProfileExtra(name: String, birthday: String?, channelId: Long?, color: Int?, surname: String? = null, email: String? = null): User
    /** Auto-delete timer of a chat in seconds (0 = off). */
    suspend fun chatTtl(peer: Long): Int
    suspend fun setChatTtl(peer: Long, seconds: Int)
    /** Link preview (title / description / picture), cached. */
    suspend fun linkPreview(url: String): LinkPreview?
    /** "Is this phone number in ICQ?" */
    suspend fun lookupPhone(phone: String): User?
    /** Lets the web version log in with the QR code shown on the computer. */
    suspend fun approveWebLogin(qr: String)
    /** Chats where somebody tagged me and I have not read it yet. */
    fun observeMentionPeers(): kotlinx.coroutines.flow.Flow<Set<Long>>
    /** @username → peer key (user ID, or -groupId), null if unknown. */
    suspend fun resolveMention(username: String): Long?
    suspend fun fetchUser(uin: Long): User?
    /** Returns the number of newly found contacts. */
    suspend fun syncPhoneContacts(keys: List<String>): Int

    /** Short texts from the server for the chat screen (command results, errors). */
    val notices: kotlinx.coroutines.flow.SharedFlow<String>
    fun observeBlocked(): Flow<List<User>>
    suspend fun searchMessages(query: String): List<FoundMessage>
    suspend fun registerPushToken(token: String)
    /** The app came to the screen (true) or went to the background (false): drives "online". */
    fun setAppActive(active: Boolean)

    // groups and channels (peer = -gid)
    fun observeGroup(peer: Long): Flow<GroupInfo?>
    suspend fun refreshGroup(peer: Long)
    /** Returns the peer key of the new group / channel. */
    suspend fun createGroup(
        name: String, members: List<Long>, channel: Boolean = false, about: String = "", public: Boolean = false,
    ): Long
    suspend fun renameGroup(peer: Long, name: String)
    suspend fun editGroup(peer: Long, name: String, about: String, public: Boolean, username: String? = null)
    suspend fun saveGroupSettings(peer: Long, settings: GroupSettings, botName: String?)
    suspend fun setBotAvatar(peer: Long, jpeg: ByteArray)
    /** Makes sure the "Saved messages" chat exists; returns its peer key (my UIN). */
    suspend fun openSaved(): Long
    suspend fun moderate(peer: Long, uin: Long, action: String, seconds: Long = 0)
    suspend fun searchChats(query: String): List<PublicChat>
    suspend fun sendPoll(peer: Long, question: String, options: List<String>, multiple: Boolean, anonymous: Boolean)
    suspend fun votePoll(message: ChatMessage, options: List<Int>): Result<Unit>
    suspend fun pollVoters(message: ChatMessage): List<Pair<Int, User>>
    /** Reports channel posts shown on screen and stores the fresh view counters. */
    suspend fun reportViews(serverIds: List<Long>)
    suspend fun groupLog(peer: Long): List<GroupLogEntry>
    /** Straight from the server (with birthday, colour and the personal channel). */
    suspend fun fetchUserFresh(uin: Long): User?
    /** Auto-delete for all my chats (seconds, 0 = off; 2 days by default). */
    /** Answer to a publication: a private message to its author with the publication quoted. */
    suspend fun replyStory(storyId: Long, text: String)
    /** I listened to this voice message / round video. */
    suspend fun markPlayed(localId: Long)
    // ---- Premium ----
    suspend fun premiumInfo(): com.icqnew.app.data.remote.PremiumInfoDto
    suspend fun buyPremium(months: Int, group: Long? = null, to: Long? = null): Int
    suspend fun sendGift(to: Long, gift: String, text: String, anonymous: Boolean): Int
    suspend fun userGifts(uin: Long): List<com.icqnew.app.data.remote.UserGiftDto>
    suspend fun defaultTtl(): Int
    suspend fun setDefaultTtl(seconds: Int): Int
    /** Latest posts of an open channel (preview before subscribing). */
    suspend fun publicPosts(gid: Long): List<ChatMessage>
    suspend fun groupStats(peer: Long, days: Int): GroupStats
    suspend fun previewChat(gid: Long): PublicChat?
    /** Joins a public group / channel, returns its peer key. */
    suspend fun joinChat(gid: Long): Long

    suspend fun setChatWallpaper(peer: Long, spec: String)

    // stories
    val stories: StateFlow<List<StoryGroup>>
    suspend fun refreshStories()
    suspend fun postStory(file: File, text: String, mime: String = "image/jpeg")
    /** Profile → Публикации: kind = image | video | repost */
    suspend fun publications(uin: Long, kind: String): List<Story>
    suspend fun repostStory(id: Long)
    suspend fun myUsernames(): Pair<List<String>, Int>
    suspend fun addUsername(name: String): Pair<List<String>, Int>
    suspend fun removeUsername(name: String): Pair<List<String>, Int>
    suspend fun setPassword(password: String, old: String?)
    suspend fun fragment(): com.icqnew.app.data.remote.FragmentDto
    suspend fun fragmentBuy(name: String)
    /** what [uin] is doing right now: "" typing, "voice" / "video" recording */
    fun typingAction(uin: Long): String
    fun notifyRecording(peer: Long, action: String)
    suspend fun markStorySeen(id: Long)
    suspend fun storyViewers(id: Long): List<User>
    suspend fun deleteStory(id: Long)

    // calls
    fun observeCalls(): Flow<List<CallRecord>>
    suspend fun clearCalls()
    suspend fun setGroupAvatar(peer: Long, jpeg: ByteArray)
    suspend fun addMembers(peer: Long, uins: List<Long>)
    suspend fun removeMember(peer: Long, uin: Long)
    suspend fun leaveGroup(peer: Long)
    /** the creator deletes his group / channel for everybody */
    suspend fun deleteGroup(peer: Long)
}

// ---- Use cases -------------------------------------------------------------

class RequestCodeUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(phone: String) = repo.requestCode(phone.trim())
}

class VerifyCodeUseCase(private val repo: AuthRepository) {
    suspend operator fun invoke(phone: String, code: String, name: String?) =
        repo.verify(phone.trim(), code.trim(), name?.trim()?.ifBlank { null })
}

class ObserveChatsUseCase(private val repo: ChatRepository) {
    operator fun invoke() = repo.observeChats()
}

class ObserveMessagesUseCase(private val repo: ChatRepository) {
    operator fun invoke(peer: Long) = repo.observeMessages(peer)
}

class SendMessageUseCase(private val repo: ChatRepository) {
    suspend operator fun invoke(peer: Long, text: String, replyTo: Long? = null) {
        val clean = text.trim()
        if (clean.isNotEmpty()) repo.sendMessage(peer, clean, replyTo)
    }
}

class SearchUsersUseCase(private val repo: ChatRepository) {
    suspend operator fun invoke(query: String): List<User> = repo.searchUsers(query.trim())
}

class SyncPhoneBookUseCase(private val chats: ChatRepository, private val book: PhoneBook) {
    suspend operator fun invoke(): Int = chats.syncPhoneContacts(book.readKeys())
}
