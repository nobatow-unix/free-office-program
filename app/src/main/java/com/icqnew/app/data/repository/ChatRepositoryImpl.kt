package com.icqnew.app.data.repository

import androidx.room.withTransaction
import com.icqnew.app.data.local.ChatPrefEntity
import com.icqnew.app.data.local.ContactEntity
import com.icqnew.app.data.local.IcqDatabase
import com.icqnew.app.data.local.KvEntity
import com.icqnew.app.data.local.MemberEntity
import com.icqnew.app.data.local.MessageEntity
import com.icqnew.app.data.local.SessionStore
import com.icqnew.app.data.notify.AppForeground
import com.icqnew.app.data.notify.Notifier
import com.icqnew.app.data.remote.ChatSocket
import com.icqnew.app.data.remote.GroupDto
import com.icqnew.app.data.remote.IcqApi
import com.icqnew.app.data.remote.MessageDto
import com.icqnew.app.data.remote.UserDto
import com.icqnew.app.data.remote.WsIn
import com.icqnew.app.data.remote.WsOut
import com.icqnew.app.data.remote.toDomain
import com.icqnew.app.data.sound.SoundPlayer
import com.icqnew.app.domain.CallRecord
import com.icqnew.app.domain.ContactCard
import com.icqnew.app.domain.LinkPreview
import com.icqnew.app.data.remote.AddContactRequest
import com.icqnew.app.domain.CallStatus
import com.icqnew.app.domain.FoundMessage
import com.icqnew.app.domain.PublicChat
import com.icqnew.app.domain.Story
import com.icqnew.app.domain.StoryGroup
import com.icqnew.app.domain.GroupSettings
import com.icqnew.app.data.remote.GroupSettingsDto
import com.icqnew.app.domain.ChatMessage
import com.icqnew.app.domain.ChatPrefs
import com.icqnew.app.domain.ChatPreview
import com.icqnew.app.domain.ChatRepository
import com.icqnew.app.domain.ConnectionState
import com.icqnew.app.domain.GroupInfo
import com.icqnew.app.domain.MessageKind
import com.icqnew.app.domain.MessageStatus
import com.icqnew.app.domain.Reaction
import com.icqnew.app.domain.User
import com.icqnew.app.domain.groupIdOf
import com.icqnew.app.domain.groupPeer
import com.icqnew.app.domain.isGroupPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Single source of truth: Room is the UI's data source, the server (REST + WebSocket)
 * only feeds Room. Unsent messages stay in Room with status SENDING (attachments keep a local
 * copy) and are re-sent after reconnect; the server de-duplicates by client_id.
 *
 * Sync is revision based: every new message, edit, deletion and reaction gets a new server
 * "rev"; after each (re)connect we ask for everything newer than the last rev we have seen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryImpl(
    private val api: IcqApi,
    private val socket: ChatSocket,
    private val db: IcqDatabase,
    private val store: SessionStore,
    private val sound: SoundPlayer,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) : ChatRepository {

    private class AttMeta(val file: File, val name: String, val mime: String, val durMs: Long)


    private val contactDao = db.contactDao()
    private val messageDao = db.messageDao()
    private val memberDao = db.memberDao()
    private val prefDao = db.prefDao()
    private val kvDao = db.kvDao()
    private val callDao = db.callDao()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val notices: SharedFlow<String> = _notices
    private val activeChat = MutableStateFlow<Long?>(null)
    private val appActive = MutableStateFlow(false)
    /** peer -> (who is typing -> until when) */
    private val typing = MutableStateFlow<Map<Long, Map<Long, Long>>>(emptyMap())
    private val syncMutex = Mutex()
    private val deliverMutex = Mutex()
    private var lastTypingSent = 0L

    override val connection: StateFlow<ConnectionState> = socket.state

    init {
        scope.launch {
            socket.events.collect { ev -> runCatching { handleEvent(ev) } }
        }
        scope.launch {
            socket.state.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    socket.send(WsOut("state", active = appActive.value))
                    runCatching { sync() }
                    resendPending()
                }
            }
        }
        scope.launch {
            appActive.collect { a -> socket.send(WsOut("state", active = a)) }
        }
    }

    // ---- observing ----------------------------------------------------------

    override fun observeChats(): Flow<List<ChatPreview>> = combine(
        messageDao.observeChats(), store.snapshots.map { it.uin }.distinctUntilChanged(),
    ) { rows, me ->
        rows.map { r ->
            ChatPreview(
                peer = User(
                    r.uin, r.name, r.avatarUrl, r.online && r.uin != me, r.lastSeen, r.about,
                    isGroup = r.isGroup, membersCount = r.members, isChannel = r.kind == "channel",
                    verified = r.verified, isSelf = r.uin == me, premium = r.premium,
                ),
                lastKind = MessageKind.fromWire(r.lastKind),
                lastStatus = when (r.lastStatus) {
                    0 -> MessageStatus.SENDING
                    2 -> MessageStatus.READ
                    3 -> MessageStatus.FAILED
                    else -> MessageStatus.SENT
                },
                lastText = if (r.lastDeleted) "Сообщение удалено"
                else if (r.lastTs == 0L) (if (r.kind == "channel") "Вы подписались на канал" else "Вы вступили в группу")
                else preview(r.lastKind, r.lastText, r.lastAttName),
                lastTs = r.lastTs,
                lastFromMe = r.lastFromMe,
                unread = r.unread,
                pinned = r.pinned,
                muted = r.muted,
                lastSender = if (r.isGroup && r.kind != "channel" && r.lastKind != MessageKind.SYSTEM.wire) r.lastSender
                else null,
            )
        }
    }

    override fun observeContacts(): Flow<List<User>> =
        combine(contactDao.observeAll(), store.snapshots.map { it.uin }.distinctUntilChanged()) { list, me ->
            list.filter { it.uin != me }.map { it.toDomain() }
        }

    override fun observePeer(uin: Long): Flow<User?> =
        combine(contactDao.observe(uin), store.snapshots.map { it.uin }.distinctUntilChanged()) { c, me ->
            c?.toDomain()?.let { if (it.uin == me) it.copy(isSelf = true, online = false) else it }
        }

    /** The "Saved messages" chat is a contact row with my own UIN. */
    private suspend fun ensureSelf() {
        val me = store.snapshot().uin
        if (me == 0L) return
        val cur = contactDao.get(me)
        if (cur == null || cur.name != SAVED_NAME) {
            contactDao.upsert(ContactEntity(me, SAVED_NAME, null, false, 0))
        }
    }

    override suspend fun openSaved(): Long {
        ensureSelf()
        return store.snapshot().uin
    }

    override fun observePrefs(peer: Long): Flow<ChatPrefs> =
        prefDao.observe(peer).map {
            ChatPrefs(it?.muted ?: false, it?.pinned ?: false, it?.blocked ?: false, it?.wallpaper ?: "")
        }

    override fun observeMessages(peer: Long): Flow<List<ChatMessage>> =
        combine(messageDao.observe(peer), store.snapshots.map { it.uin }.distinctUntilChanged()) { list, me ->
            list.map { it.toDomain(me) }
        }

    override fun observeTyping(peer: Long): Flow<List<Long>> =
        typing.map { it[peer] ?: emptyMap() }.distinctUntilChanged().flatMapLatest { m ->
            flow {
                while (true) {
                    val now = System.currentTimeMillis()
                    val who = m.filterValues { it > now }.keys.toList()
                    emit(who)
                    if (who.isEmpty()) break
                    delay(500)
                }
            }
        }.distinctUntilChanged()

    override fun observeTypingPeers(): Flow<Set<Long>> = typing.flatMapLatest { map ->
        flow {
            while (true) {
                val now = System.currentTimeMillis()
                val set = map.filterValues { users -> users.values.any { it > now } }.keys
                emit(set)
                if (set.isEmpty()) break
                delay(1_000)
            }
        }
    }.distinctUntilChanged()

    override fun observeGroup(peer: Long): Flow<GroupInfo?> =
        combine(
            contactDao.observe(peer),
            memberDao.observe(groupIdOf(peer)),
            store.snapshots.map { it.uin }.distinctUntilChanged(),
        ) { g, members, me ->
            if (g == null) null
            else {
                val mine = members.firstOrNull { it.uin == me }
                GroupInfo(
                    id = groupIdOf(peer),
                    name = g.name,
                    avatarUrl = g.avatarUrl,
                    ownerUin = g.owner,
                    members = members.map {
                        User(
                            it.uin, it.name, it.avatarUrl, it.online, it.lastSeen, it.about,
                            role = it.role, mutedUntil = it.mutedUntil, verified = it.verified, username = it.username,
                        )
                    },
                    isChannel = g.kind == "channel",
                    about = g.about,
                    isPublic = g.isPublic,
                    count = g.members,
                    myRole = mine?.role ?: if (g.owner == me) "owner" else "member",
                    myMutedUntil = mine?.mutedUntil ?: 0,
                    username = g.username,
                    verified = g.verified,
                    settings = parseSettings(g.settings),
                    botName = g.botName.ifBlank { "ICQ Бот" },
                    botAvatar = g.botAvatar,
                )
            }
        }

    override fun observeBlocked(): Flow<List<User>> =
        prefDao.observeBlocked().map { list -> list.map { it.toDomain() } }

    override suspend fun searchMessages(query: String): List<FoundMessage> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return messageDao.search("%" + q.replace("%", "") + "%").map {
            FoundMessage(it.peerUin, it.name ?: "Чат", it.text, it.ts)
        }
    }

    override fun setAppActive(active: Boolean) {
        appActive.value = active
    }

    override suspend fun registerPushToken(token: String) {
        store.setPushToken(token)
        if (store.snapshot().token != null) runCatching { api.pushToken(token) }
    }

    override fun observeCalls(): Flow<List<CallRecord>> = callDao.observe().map { rows ->
        rows.map {
            CallRecord(
                it.id, it.peer, it.name ?: "ID ${it.peer}", it.avatarUrl, it.outgoing, it.video, it.ts, it.duration,
                runCatching { CallStatus.valueOf(it.status) }.getOrDefault(CallStatus.DONE),
            )
        }
    }

    override suspend fun clearCalls() = callDao.clear()

    // ---- sending ------------------------------------------------------------

    private fun target(peer: Long, type: String): WsOut =
        if (isGroupPeer(peer)) WsOut(type, group = groupIdOf(peer)) else WsOut(type, to = peer)

    override fun notifyTyping(peer: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 3_000) return
        lastTypingSent = now
        scope.launch { if (store.snapshot().sendTyping) socket.send(target(peer, "typing")) }
    }

    override suspend fun sendMessage(peer: Long, text: String, replyTo: Long?) =
        enqueue(peer, MessageKind.TEXT, text, null, replyTo, null, null)

    override suspend fun sendSticker(peer: Long, emoji: String, replyTo: Long?) =
        enqueue(peer, MessageKind.STICKER, emoji, null, replyTo, null, null)

    override suspend fun sendAttachment(
        peer: Long, kind: MessageKind, file: File, name: String, mime: String, durMs: Long, replyTo: Long?,
        caption: String,
    ) = enqueue(peer, kind, caption.trim().take(4000), AttMeta(file, name, mime, durMs), replyTo, null, null)

    override suspend fun forward(message: ChatMessage, targets: List<Long>, originalSender: String) {
        for (peer in targets) {
            val clientId = UUID.randomUUID().toString()
            messageDao.insert(
                MessageEntity(
                    serverId = null,
                    clientId = clientId,
                    peerUin = peer,
                    fromMe = true,
                    fromUin = store.snapshot().uin,
                    text = message.text,
                    ts = System.currentTimeMillis(),
                    status = 0,
                    isRead = true,
                    kind = message.kind.wire,
                    attId = message.attId,
                    attName = message.attName,
                    attSize = message.attSize,
                    attMime = message.attMime,
                    attDur = message.attDurMs,
                    localPath = message.localPath,
                    fwdName = message.fwdName ?: originalSender,
                )
            )
            ensurePeer(peer)
            scope.launch { deliver(clientId) }
        }
        sound.playSend()
    }

    private suspend fun enqueue(
        peer: Long, kind: MessageKind, text: String, att: AttMeta?, replyTo: Long?, attId: String?, fwd: String?,
    ) {
        val clientId = UUID.randomUUID().toString()
        messageDao.insert(
            MessageEntity(
                serverId = null,
                clientId = clientId,
                peerUin = peer,
                fromMe = true,
                fromUin = store.snapshot().uin,
                text = text,
                ts = System.currentTimeMillis(),
                status = 0,
                isRead = true,
                kind = kind.wire,
                attId = attId,
                attName = att?.name,
                attSize = att?.file?.length() ?: 0L,
                attMime = att?.mime,
                attDur = att?.durMs ?: 0L,
                localPath = att?.file?.absolutePath,
                replyTo = replyTo,
                fwdName = fwd,
            )
        )
        saveDraft(peer, "")
        sound.playSend()
        ensurePeer(peer)
        if (att == null) deliver(clientId) else scope.launch { deliver(clientId) }
    }

    /** Makes sure a person we write to is in the local contact list (needed for the chat list). */
    private suspend fun ensurePeer(peer: Long) {
        if (isGroupPeer(peer) || contactDao.get(peer) != null) return
        scope.launch {
            runCatching { contactDao.upsert(api.addContact(peer).toEntity()) }
        }
    }

    /** Uploads the attachment (if any) and pushes the message through the WebSocket. */
    private suspend fun deliver(clientId: String) {
        deliverMutex.withLock {
            val m = messageDao.byClientId(clientId) ?: return@withLock
            if (m.status != 0) return@withLock
            var attId = m.attId
            if (MessageKind.fromWire(m.kind).isAttachment && attId == null) {
                val f = m.localPath?.let { File(it) }
                if (f == null || !f.exists()) {
                    messageDao.markFailed(clientId, "Файл не найден")
                    return@withLock
                }
                val up = try {
                    api.upload(f, m.attName ?: f.name, m.attMime ?: "application/octet-stream", m.attDur)
                } catch (e: com.icqnew.app.data.remote.ApiException) {
                    messageDao.markFailed(clientId, e.message ?: "Ошибка загрузки")
                    return@withLock
                } catch (e: Exception) {
                    return@withLock // no network: stays queued, re-sent after reconnect
                }
                attId = up.id
                messageDao.setAttId(clientId, up.id)
            }
            socket.send(
                target(m.peerUin, "message").copy(
                    text = m.text,
                    clientId = clientId,
                    kind = m.kind,
                    attId = attId,
                    replyTo = m.replyTo,
                    fwdName = m.fwdName,
                    poll = if (m.kind == MessageKind.POLL.wire) m.poll?.let { decodePollDto(it) }?.let { p ->
                        com.icqnew.app.data.remote.WsPoll(p.options.map { it.text }, p.multiple, p.anonymous)
                    } else null,
                )
            )
            Unit
        }
    }

    override suspend fun editMessage(message: ChatMessage, text: String): Boolean {
        val sid = message.serverId ?: return false
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val ok = socket.send(WsOut("edit", id = sid, text = clean))
        if (ok) messageDao.byId(message.id)?.let { messageDao.update(it.copy(text = clean, edited = true)) }
        return ok
    }

    override suspend fun deleteMessage(message: ChatMessage, forEveryone: Boolean): Boolean {
        val sid = message.serverId
        if (!forEveryone || sid == null) {
            messageDao.deleteById(message.id)
            return true
        }
        val ok = socket.send(WsOut("delete", id = sid))
        if (ok && contactDao.get(message.peerUin)?.kind == "channel") {
            messageDao.deleteById(message.id) // channel posts vanish without a "deleted" stub
        } else if (ok) {
            messageDao.byId(message.id)?.let {
                messageDao.update(
                    it.copy(text = "", deleted = true, kind = "text", attId = null, attName = null, reactions = "")
                )
            }
        }
        return ok
    }

    override suspend fun react(message: ChatMessage, emoji: String): Boolean {
        val sid = message.serverId ?: return false
        return socket.send(WsOut("react", id = sid, emoji = emoji))
    }

    override suspend fun retry(message: ChatMessage) {
        val e = messageDao.byId(message.id) ?: return
        messageDao.markSending(e.clientId)
        deliver(e.clientId)
    }

    override suspend fun markRead(peer: Long) {
        if (messageDao.markRead(peer) > 0) {
            notifier.cancel(peer)
            if (store.snapshot().sendRead) socket.send(target(peer, "read"))
        }
    }

    override fun setActiveChat(uin: Long?) {
        activeChat.value = uin
    }

    // ---- local chat options --------------------------------------------------

    private suspend fun pref(peer: Long): ChatPrefEntity = prefDao.get(peer) ?: ChatPrefEntity(peer)

    override suspend fun setPinned(peer: Long, pinned: Boolean) = prefDao.put(pref(peer).copy(pinned = pinned))

    override suspend fun setMuted(peer: Long, muted: Boolean) = prefDao.put(pref(peer).copy(muted = muted))

    override suspend fun setBlocked(uin: Long, blocked: Boolean) {
        api.setBlock(uin, blocked)
        prefDao.put(pref(uin).copy(blocked = blocked))
    }

    override suspend fun setChatWallpaper(peer: Long, spec: String) = prefDao.put(pref(peer).copy(wallpaper = spec))

    private suspend fun markOnline(uin: Long) {
        val now = System.currentTimeMillis()
        runCatching {
            contactDao.setPresence(uin, true, now)
            memberDao.setPresence(uin, true, now)
        }
    }

    // ---- stories ------------------------------------------------------------

    private val _stories = MutableStateFlow<List<StoryGroup>>(emptyList())
    override val stories: StateFlow<List<StoryGroup>> = _stories

    override suspend fun refreshStories() {
        _stories.value = api.stories().map { g ->
            StoryGroup(
                g.user.toDomain(),
                g.stories.map { Story(it.id, it.uin, it.attId, it.text, it.ts, it.seen, it.views, it.myReaction, it.reactions, it.comments) },
                g.allSeen,
            )
        }
    }

    override suspend fun postStory(file: File, text: String) {
        val up = api.upload(file, file.name, "image/jpeg", 0)
        api.addStory(up.id, text)
        refreshStories()
    }

    override suspend fun markStorySeen(id: Long) {
        runCatching { api.viewStory(id) }
        _stories.value = _stories.value.map { g ->
            val list = g.stories.map { if (it.id == id) it.copy(seen = true) else it }
            g.copy(stories = list, allSeen = g.allSeen || list.all { it.seen })
        }
    }

    override suspend fun storyViewers(id: Long): List<User> =
        api.storyViewers(id).map { User(it.uin, it.name, it.avatarUrl, false, it.viewed, verified = it.verified, about = it.reaction) }

    override suspend fun deleteStory(id: Long) {
        api.deleteStory(id)
        refreshStories()
    }

    override suspend fun clearHistory(peer: Long, forAll: Boolean) {
        if (forAll) {
            if (isGroupPeer(peer)) api.clearChat(null, groupIdOf(peer), true)
            else api.clearChat(peer, null, true)
        }
        messageDao.deleteChat(peer)
    }

    override suspend fun deleteChat(peer: Long, forAll: Boolean) {
        if (forAll && !isGroupPeer(peer)) api.clearChat(peer, null, true)
        messageDao.deleteChat(peer)
        prefDao.put(pref(peer).copy(pinned = false, draft = ""))
    }

    override suspend fun draft(peer: Long): String = prefDao.get(peer)?.draft ?: ""

    override suspend fun saveDraft(peer: Long, text: String) {
        val p = pref(peer)
        if (p.draft != text) prefDao.put(p.copy(draft = text))
    }

    // ---- people -------------------------------------------------------------

    override suspend fun searchUsers(query: String): List<User> =
        if (query.isBlank()) emptyList() else api.search(query).map { it.toDomain() }

    override suspend fun addContact(uin: Long) {
        contactDao.upsert(api.addContact(uin).toEntity())
    }

    private fun UserDto.toCard() = ContactCard(
        user = toDomain(), phone = phone, alias = alias, iAdded = contact, addedMe = addedMe, addedAt = addedAt,
    )

    override suspend fun contactCards(): List<ContactCard> {
        val list = api.contacts()
        list.forEach { contactDao.upsert(it.toEntity()) }
        return list.map { it.toCard() }
    }

    override suspend fun incomingContacts(): List<ContactCard> = api.incomingContacts().map { it.toCard() }

    override suspend fun addContactBy(phone: String?, query: String?, name: String?, sharePhone: Boolean): ContactCard {
        val u = api.addContactBy(
            AddContactRequest(
                phone = phone?.takeIf { it.isNotBlank() }, query = query?.takeIf { it.isNotBlank() },
                name = name?.takeIf { it.isNotBlank() }, sharePhone = sharePhone,
            )
        )
        contactDao.upsert(u.toEntity())
        return u.toCard()
    }

    override suspend fun removeContact(uin: Long) {
        api.removeContact(uin)
    }

    override suspend fun contactCard(uin: Long): ContactCard? = runCatching { api.user(uin).toCard() }.getOrNull()

    private val _contactEvents = MutableSharedFlow<ContactCard>(extraBufferCapacity = 8)
    override val contactEvents: SharedFlow<ContactCard> = _contactEvents

    override suspend fun detectDialCode(sim: String): String = runCatching { api.geo(sim).dial }.getOrDefault("")

    override suspend fun updateProfileExtra(
        name: String, birthday: String?, channelId: Long?, color: Int?, surname: String?, email: String?,
    ): User = api.updateProfileExtra(name, birthday, channelId, color, surname, email).toDomain()

    override suspend fun chatTtl(peer: Long): Int = runCatching {
        if (peer < 0) api.getTtl(null, -peer).seconds else api.getTtl(peer, null).seconds
    }.getOrDefault(0)

    override suspend fun setChatTtl(peer: Long, seconds: Int) {
        if (peer < 0) api.setTtl(null, -peer, seconds) else api.setTtl(peer, null, seconds)
    }

    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, LinkPreview>()

    override suspend fun linkPreview(url: String): LinkPreview? {
        previewCache[url]?.let { return it.takeIf { p -> p.title.isNotBlank() } }
        val d = runCatching { api.preview(url) }.getOrNull() ?: return null
        val base = store.snapshot().serverUrl
        val p = com.icqnew.app.domain.LinkPreview(
            url = url, title = d.title.orEmpty(), description = d.description.orEmpty(),
            image = d.image?.let { if (it.startsWith("/")) base + it else it }, site = d.site.orEmpty(),
            type = d.type, peer = d.peer, count = d.count, verified = d.verified,
        )
        previewCache[url] = p
        return p.takeIf { it.title.isNotBlank() || it.description.isNotBlank() }
    }

    override suspend fun lookupPhone(phone: String): User? =
        runCatching { api.lookup(phone) }.getOrNull()?.takeIf { it.found }?.user?.toDomain()

    override suspend fun resolveMention(username: String): Long? {
        val un = username.trim().removePrefix("@")
        val me = store.snapshot()
        if (me.username.equals(un, ignoreCase = true)) return me.uin // my own @name
        val r = runCatching { api.resolve(un) }.getOrNull()
        r?.user?.let { u ->
            // only cache people we do not know yet (never overwrite an existing chat entry)
            if (u.uin != me.uin && contactDao.get(u.uin) == null) runCatching { contactDao.upsert(u.toEntity()) }
            return u.uin
        }
        r?.group?.let { return -it.id }
        // offline / old server: people we already know locally
        return runCatching { memberDao.uinByUsername(un) ?: contactDao.uinByUsername(un) }.getOrNull()
    }

    override suspend fun approveWebLogin(qr: String) {
        api.approveQr(qr)
    }

    override suspend fun storyComments(id: Long): List<com.icqnew.app.domain.StoryComment> =
        api.storyComments(id).map { com.icqnew.app.domain.StoryComment(it.id, it.text, it.ts, it.user.toDomain()) }

    override suspend fun addStoryComment(id: Long, text: String): com.icqnew.app.domain.StoryComment {
        val c = api.addStoryComment(id, text)
        _stories.value = _stories.value.map { g ->
            g.copy(stories = g.stories.map { if (it.id == id) it.copy(comments = it.comments + 1) else it })
        }
        return com.icqnew.app.domain.StoryComment(c.id, c.text, c.ts, c.user.toDomain())
    }

    override suspend fun deleteStoryComment(id: Long, commentId: Long) {
        api.deleteStoryComment(id, commentId)
    }

    override suspend fun reactStory(id: Long, emoji: String) {
        api.reactStory(id, emoji)
        _stories.value = _stories.value.map { g ->
            g.copy(stories = g.stories.map { if (it.id == id) it.copy(myReaction = emoji, seen = true) else it })
        }
    }

    override suspend fun fetchUser(uin: Long): User? {
        contactDao.get(uin)?.let { local ->
            scope.launch { runCatching { refreshUser(api.user(uin)) } }
            return local.toDomain()
        }
        memberDao.anyByUin(uin)?.let { m ->
            return User(m.uin, m.name, m.avatarUrl, m.online, m.lastSeen, m.about)
        }
        return runCatching { api.user(uin).toDomain() }.getOrNull()
    }

    override suspend fun syncPhoneContacts(keys: List<String>): Int {
        if (keys.isEmpty()) return 0
        var added = 0
        for (u in api.syncContacts(keys)) {
            if (contactDao.get(u.uin) == null) added++
            contactDao.upsert(u.toEntity())
        }
        return added
    }

    private suspend fun refreshUser(u: UserDto) {
        if (contactDao.get(u.uin) != null) contactDao.upsert(u.toEntity())
        memberDao.updateProfile(u.uin, u.name, u.avatarUrl, u.about)
    }

    // ---- groups -------------------------------------------------------------

    private suspend fun saveGroup(g: GroupDto) {
        db.withTransaction {
            contactDao.upsert(
                ContactEntity(
                    uin = groupPeer(g.id), name = g.name, avatarUrl = g.avatarUrl, online = false, lastSeen = 0,
                    about = g.about, isGroup = true, members = g.count, owner = g.owner, kind = g.kind,
                    isPublic = g.public, username = g.username, verified = g.verified,
                    settings = settingsJson.encodeToString(GroupSettingsDto.serializer(), g.settings),
                    botName = g.botName, botAvatar = g.botAvatar,
                )
            )
            if (g.members.isNotEmpty() || g.kind == "channel") {
                memberDao.clear(g.id)
                memberDao.insertAll(g.members.mapIndexed { i, u ->
                    MemberEntity(g.id, u.uin, u.name, u.avatarUrl, u.online, u.lastSeen, u.about, i, u.role, u.mutedUntil, u.verified, u.username)
                })
            }
        }
    }

    private suspend fun dropGroup(gid: Long) {
        db.withTransaction {
            messageDao.deleteChat(groupPeer(gid))
            memberDao.clear(gid)
            contactDao.delete(groupPeer(gid))
        }
        notifier.cancel(groupPeer(gid))
    }

    override suspend fun refreshGroup(peer: Long) {
        saveGroup(api.group(groupIdOf(peer)))
    }

    override suspend fun createGroup(
        name: String, members: List<Long>, channel: Boolean, about: String, public: Boolean,
    ): Long {
        val g = api.createGroup(name, members, if (channel) "channel" else "group", about, public)
        saveGroup(g)
        return groupPeer(g.id)
    }

    override suspend fun renameGroup(peer: Long, name: String) =
        saveGroup(api.editGroup(groupIdOf(peer), name, null, null))

    override suspend fun editGroup(peer: Long, name: String, about: String, public: Boolean, username: String?) =
        saveGroup(api.editGroup(groupIdOf(peer), name, about, public, username))

    override suspend fun saveGroupSettings(peer: Long, settings: GroupSettings, botName: String?) {
        val dto = GroupSettingsDto(
            settings.onlyAdminsAdd, settings.slowMode, settings.membersMedia, settings.reactions,
            settings.welcome, settings.helpOnOpen, settings.signatures,
        )
        saveGroup(api.groupSettings(groupIdOf(peer), dto, botName))
    }

    override suspend fun setBotAvatar(peer: Long, jpeg: ByteArray) = saveGroup(api.botAvatar(groupIdOf(peer), jpeg))

    private val settingsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun parseSettings(raw: String): GroupSettings {
        val d = runCatching { settingsJson.decodeFromString(GroupSettingsDto.serializer(), raw) }
            .getOrDefault(GroupSettingsDto())
        return GroupSettings(d.onlyAdminsAdd, d.slowMode, d.membersMedia, d.reactions, d.welcome, d.helpOnOpen, d.signatures)
    }

    override suspend fun moderate(peer: Long, uin: Long, action: String, seconds: Long) {
        val g = api.moderate(groupIdOf(peer), uin, action, seconds)
        if (g.id != 0L) saveGroup(g)
    }

    private fun GroupDto.toPublic() =
        PublicChat(id, name, avatarUrl, about, kind == "channel", count, member, verified, username)

    override suspend fun searchChats(query: String): List<PublicChat> = api.searchChats(query).map { it.toPublic() }

    override suspend fun sendPoll(peer: Long, question: String, options: List<String>, multiple: Boolean, anonymous: Boolean) {
        val clientId = UUID.randomUUID().toString()
        val dto = com.icqnew.app.data.remote.PollDto(
            options.map { com.icqnew.app.data.remote.PollOptionDto(it, 0) }, 0, multiple, anonymous, false, emptyList(),
        )
        messageDao.insert(
            MessageEntity(
                serverId = null, clientId = clientId, peerUin = peer, fromMe = true, fromUin = store.snapshot().uin,
                text = question.trim(), ts = System.currentTimeMillis(), status = 0, isRead = true,
                kind = MessageKind.POLL.wire,
                poll = pollJson.encodeToString(com.icqnew.app.data.remote.PollDto.serializer(), dto),
            )
        )
        sound.playSend()
        deliver(clientId)
    }

    override suspend fun votePoll(message: ChatMessage, options: List<Int>): Result<Unit> = runCatching {
        val sid = message.serverId ?: error("Сообщение ещё не отправлено")
        val m = api.votePoll(sid, options)
        applyMessage(m, firstSync = false)
        Unit
    }

    override suspend fun pollVoters(message: ChatMessage): List<Pair<Int, User>> {
        val sid = message.serverId ?: return emptyList()
        return api.pollVoters(sid).map { it.opt to it.user.toDomain() }
    }

    override suspend fun reportViews(serverIds: List<Long>) {
        if (serverIds.isEmpty()) return
        runCatching { api.postViews(serverIds) }.getOrNull()?.forEach { (id, n) ->
            id.toLongOrNull()?.let { messageDao.setViews(it, n) }
        }
    }

    override suspend fun premiumInfo() = api.premium()

    override suspend fun buyPremium(months: Int, group: Long?, to: Long?): Int =
        api.buyPremium(com.icqnew.app.data.remote.BuyPremiumRequest(months, group, to)).balance

    override suspend fun sendGift(to: Long, gift: String, text: String, anonymous: Boolean): Int =
        api.sendGift(com.icqnew.app.data.remote.SendGiftRequest(to, gift, text, anonymous)).balance

    override suspend fun userGifts(uin: Long) = api.userGifts(uin)

    override suspend fun markPlayed(localId: Long) {
        val e = messageDao.byId(localId) ?: return
        if (e.played) return
        messageDao.setPlayed(localId)
        if (!e.fromMe) e.serverId?.let { socket.send(WsOut("played", id = it)) }
    }

    override suspend fun replyStory(storyId: Long, text: String) {
        applyMessage(api.replyStory(storyId, text), firstSync = true)
    }

    override suspend fun defaultTtl(): Int = api.defaultTtl().seconds

    override suspend fun setDefaultTtl(seconds: Int): Int = api.setDefaultTtl(seconds).seconds

    override suspend fun fetchUserFresh(uin: Long): User? = runCatching { api.user(uin).toDomain() }.getOrNull()

    override suspend fun publicPosts(gid: Long): List<ChatMessage> = runCatching {
        val me = store.snapshot().uin
        api.publicPosts(gid).map { d ->
            MessageEntity(
                id = d.id, serverId = d.id, clientId = d.clientId, peerUin = groupPeer(gid), fromMe = d.fromUin == me,
                fromUin = d.fromUin, text = d.text, ts = d.ts, status = 1, isRead = true, kind = d.kind,
                attId = d.attId, attName = d.attName, attSize = d.attSize, attMime = d.attMime, attDur = d.attDur,
                reactions = encodeReactions(d), views = d.views, poll = mergePoll(d.poll, null),
            ).toDomain(me)
        }
    }.getOrDefault(emptyList())

    override suspend fun groupLog(peer: Long): List<com.icqnew.app.domain.GroupLogEntry> =
        api.groupLog(groupIdOf(peer)).map {
            com.icqnew.app.domain.GroupLogEntry(it.action, it.ts, it.user.toDomain(), it.actor?.toDomain())
        }

    override suspend fun groupStats(peer: Long, days: Int): com.icqnew.app.domain.GroupStats {
        val d = api.groupStats(groupIdOf(peer), days)
        return com.icqnew.app.domain.GroupStats(
            d.members, d.views, d.reactions, d.avgViews, d.joined, d.left,
            d.series.map { com.icqnew.app.domain.StatDay(it.day, it.joined, it.left, it.views) },
            d.posts.map { com.icqnew.app.domain.StatPost(it.id, it.text, it.kind, it.ts, it.views, it.reactions) },
            d.topReactions.map { it.emoji to it.count },
        )
    }

    override suspend fun previewChat(gid: Long): PublicChat? = runCatching { api.group(gid).toPublic() }.getOrNull()

    override suspend fun joinChat(gid: Long): Long {
        saveGroup(api.joinGroup(gid))
        // the posts written before I joined: so the channel is in «Каналы» at once, with its content
        runCatching { api.publicPosts(gid, 60) }.getOrNull()?.sortedBy { it.id }?.forEach {
            runCatching { applyMessage(it, firstSync = true) }
        }
        return groupPeer(gid)
    }

    override suspend fun setGroupAvatar(peer: Long, jpeg: ByteArray) =
        saveGroup(api.groupAvatar(groupIdOf(peer), jpeg))

    override suspend fun addMembers(peer: Long, uins: List<Long>) =
        saveGroup(api.addMembers(groupIdOf(peer), uins))

    override suspend fun removeMember(peer: Long, uin: Long) {
        api.removeMember(groupIdOf(peer), uin)
        runCatching { refreshGroup(peer) }
    }

    override suspend fun leaveGroup(peer: Long) {
        api.removeMember(groupIdOf(peer), store.snapshot().uin)
        dropGroup(groupIdOf(peer))
    }

    // ---- sync & real-time ---------------------------------------------------

    private suspend fun handleEvent(ev: WsIn) {
        when (ev.type) {
            "message" -> ev.message?.let {
                // whoever just wrote is online right now (fixes a stale "был(а) ..." in the header)
                if (it.fromUin != store.snapshot().uin && it.fromUin != com.icqnew.app.domain.BOT_UIN) markOnline(it.fromUin)
                onLiveMessage(it)
            }
            "update" -> ev.message?.let { applyMessage(it, firstSync = false) }
            "ack" -> {
                val cid = ev.clientId
                val id = ev.id
                val ts = ev.ts
                if (cid != null && id != null && ts != null) messageDao.markSent(cid, id, ts)
            }
            "failed" -> {
                val cid = ev.clientId
                if (cid != null) messageDao.markFailed(cid, ev.text ?: "Не доставлено")
                ev.text?.let { _notices.tryEmit(it) }
            }
            "command_done" -> {
                ev.clientId?.let { messageDao.deletePending(it) }
                ev.text?.let { _notices.tryEmit(it) }
            }
            "contact_added" -> ev.user?.let { u ->
                _contactEvents.tryEmit(u.toCard())
                _notices.tryEmit("${u.name} добавил(а) вас в контакты")
            }
            "presence" -> {
                val uin = ev.uin
                if (uin != null) {
                    val online = ev.online ?: false
                    val seen = ev.lastSeen ?: System.currentTimeMillis()
                    contactDao.setPresence(uin, online, seen)
                    memberDao.setPresence(uin, online, seen)
                }
            }
            "typing" -> {
                val uin = ev.uin
                if (uin != null) {
                    if (uin != com.icqnew.app.domain.BOT_UIN) markOnline(uin)
                    val peer = ev.gid?.let { groupPeer(it) } ?: uin
                    val until = System.currentTimeMillis() + 4_500
                    typing.update { all -> all + (peer to ((all[peer] ?: emptyMap()) + (uin to until))) }
                }
            }
            "read" -> {
                val upto = ev.upto
                val peer = ev.gid?.let { groupPeer(it) } ?: ev.uin
                if (peer != null && upto != null) messageDao.markReadUpTo(peer, upto)
            }
            "profile" -> ev.user?.let { refreshUser(it) }
            "contact" -> ev.user?.let { contactDao.upsert(it.toEntity()) }
            "gone" -> {
                val uin = ev.uin
                if (uin != null) {
                    contactDao.delete(uin)
                    messageDao.deleteChat(uin)
                }
            }
            "group" -> ev.group?.let { saveGroup(it) }
            "group_gone" -> ev.id?.let { dropGroup(it) }
            "stories" -> runCatching { refreshStories() }
            "me" -> ev.user?.let { u ->
                store.updateProfile(u.name, u.avatarUrl, u.about, u.phone)
                store.setIdentity(u.username, u.verified)
            }
            "chat_cleared" -> {
                val peer = ev.id
                val upto = ev.upto
                if (peer != null && upto != null) {
                    messageDao.deleteUpTo(peer, upto)
                    notifier.cancel(peer)
                }
            }
        }
    }

    private fun peerOf(m: MessageDto, me: Long): Long = when {
        m.groupId != null -> groupPeer(m.groupId)
        m.fromUin == me -> m.toUin
        else -> m.fromUin
    }

    /** Keeps my own vote when an update (sent to everybody) does not carry it. */
    private fun mergePoll(incoming: com.icqnew.app.data.remote.PollDto?, stored: String?): String? {
        if (incoming == null) return stored
        val my = incoming.my ?: stored?.let { decodePollDto(it)?.my } ?: emptyList()
        return pollJson.encodeToString(com.icqnew.app.data.remote.PollDto.serializer(), incoming.copy(my = my))
    }

    private fun encodeReactions(m: MessageDto): String = m.reactions.joinToString(";") { "${it.uin}:${it.emoji}" }

    /**
     * Inserts a new message or updates the one we already have (edit / delete / reactions / ack).
     * Returns the stored row if the message is NEW, null otherwise.
     */
    private suspend fun applyMessage(m: MessageDto, firstSync: Boolean): MessageEntity? {
        val me = store.snapshot().uin
        val mine = m.fromUin == me
        val peer = peerOf(m, me)
        if (m.kind == "gone") { // auto-delete timer fired: the message vanishes for everybody
            messageDao.deleteByServerId(m.id)
            return null
        }
        val existing = messageDao.byServerId(m.id) ?: if (mine) messageDao.byClientId(m.clientId) else null
        if (existing != null) {
            messageDao.update(
                existing.copy(
                    serverId = m.id,
                    text = m.text,
                    ts = m.ts,
                    kind = m.kind,
                    attId = m.attId,
                    attName = m.attName,
                    attSize = m.attSize,
                    attMime = m.attMime,
                    attDur = m.attDur,
                    replyTo = m.replyTo,
                    edited = m.edited,
                    deleted = m.deleted,
                    fwdName = m.fwdName,
                    reactions = encodeReactions(m),
                    failReason = null,
                    views = maxOf(existing.views, m.views),
                    played = existing.played || m.played,
                    poll = mergePoll(m.poll, existing.poll),
                    status = when {
                        existing.status == 2 || (mine && m.read) -> 2
                        else -> 1
                    },
                )
            )
            return null
        }
        val isRead = mine || firstSync || activeChat.value == peer || m.kind == MessageKind.SYSTEM.wire
        val e = MessageEntity(
            serverId = m.id,
            clientId = m.clientId,
            peerUin = peer,
            fromMe = mine,
            fromUin = m.fromUin,
            text = m.text,
            ts = m.ts,
            status = if (mine && m.read) 2 else 1,
            isRead = isRead,
            kind = m.kind,
            attId = m.attId,
            attName = m.attName,
            attSize = m.attSize,
            attMime = m.attMime,
            attDur = m.attDur,
            replyTo = m.replyTo,
            edited = m.edited,
            deleted = m.deleted,
            fwdName = m.fwdName,
            reactions = encodeReactions(m),
            views = m.views,
            poll = mergePoll(m.poll, null),
            storyAtt = m.story?.let { it.attId ?: "" },
            played = m.played,
        )
        return if (messageDao.insert(e) == -1L) null else e
    }

    private suspend fun ensureKnown(peer: Long) {
        if (contactDao.get(peer) != null) return
        if (isGroupPeer(peer)) runCatching { refreshGroup(peer) }
        else runCatching { contactDao.upsert(api.user(peer).toEntity()) }
    }

    private suspend fun onLiveMessage(m: MessageDto) {
        val e = applyMessage(m, firstSync = false) ?: return
        ensureKnown(e.peerUin)
        if (e.fromMe || e.kind == MessageKind.SYSTEM.wire) return
        typing.update { all ->
            val users = (all[e.peerUin] ?: emptyMap()) - e.fromUin
            if (users.isEmpty()) all - e.peerUin else all + (e.peerUin to users)
        }
        val viewing = activeChat.value == e.peerUin
        val foreground = AppForeground.isForeground()
        val mentioned = isMention(e.text)
        // a muted chat stays quiet — unless somebody tagged me with @username
        val muted = prefDao.get(e.peerUin)?.muted == true && !mentioned
        if (viewing && foreground) {
            if (store.snapshot().sendRead) socket.send(target(e.peerUin, "read"))
            sound.playIncoming()
            return
        }
        if (!muted) {
            sound.playIncoming()
            notifyAbout(e, mentioned)
        }
    }

    private suspend fun isMention(text: String): Boolean {
        val un = store.snapshot().username
        return un.isNotBlank() && Regex("(?<![\\w@])@" + Regex.escape(un) + "(?!\\w)", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeMentionPeers(): Flow<Set<Long>> =
        store.snapshots.map { it.username }.distinctUntilChanged().flatMapLatest { un ->
            if (un.isBlank()) kotlinx.coroutines.flow.flowOf(emptySet())
            else messageDao.observeMentionPeers("%@$un%").map { it.toSet() }
        }

    private suspend fun notifyAbout(e: MessageEntity, mentioned: Boolean = false) {
        if (AppForeground.isForeground() && activeChat.value == e.peerUin) return
        val chat = contactDao.get(e.peerUin)
        val text = if (!store.snapshot().notifyPreview) "Новое сообщение"
        else if (e.deleted) "Сообщение удалено" else preview(e.kind, e.text, e.attName)
        if (isGroupPeer(e.peerUin) && chat?.kind != "channel") {
            val sender = memberDao.get(groupIdOf(e.peerUin), e.fromUin)?.name
            val title = (chat?.name ?: "Группа") + if (mentioned) " · вас упомянули" else ""
            notifier.showMessage(title, if (sender != null) "$sender: $text" else text, e.peerUin)
        } else {
            // nobody answers into a channel from a notification (only its admins write there)
            notifier.showMessage(chat?.name ?: "ICQ", text, e.peerUin, canReply = chat?.kind != "channel")
        }
    }

    private suspend fun sync() {
        syncMutex.withLock {
            runCatching {
                val me = api.me()
                store.setServerPrivacy(me.searchable, me.showOnline, me.avatarPrivacy, me.phonePrivacy)
                store.updateProfile(me.name, me.avatarUrl, me.about, me.phone)
                store.setIdentity(me.username, me.verified)
            }
            ensureSelf()
            contactDao.upsertAll(api.contacts().map { it.toEntity() })

            runCatching {
                val groups = api.groups()
                val ids = groups.map { it.id }.toSet()
                for (g in groups) saveGroup(g)
                for (peer in contactDao.groupPeers()) if (groupIdOf(peer) !in ids) dropGroup(groupIdOf(peer))
            }
            runCatching {
                val blocked = api.blocks()
                prefDao.clearBlocked()
                for (b in blocked) prefDao.put(pref(b).copy(blocked = true))
            }

            var rev = kvDao.get(KEY_REV) ?: 0L
            val firstSync = rev == 0L // fresh login: do not show old history as unread
            val fresh = mutableListOf<MessageEntity>()
            while (true) {
                val r = api.updates(rev)
                for (m in r.messages) {
                    applyMessage(m, firstSync)?.let { if (!it.fromMe && !it.isRead) fresh.add(it) }
                }
                for (cl in r.clears) {
                    messageDao.deleteUpTo(cl.peer, cl.upto)
                    fresh.removeAll { it.peerUin == cl.peer && (it.serverId ?: 0L) <= cl.upto }
                }
                rev = maxOf(rev, r.rev)
                kvDao.put(KvEntity(KEY_REV, rev))
                if (!r.more || r.messages.isEmpty()) break
            }
            for (mark in api.readMarks()) messageDao.markReadUpTo(mark.peer, mark.upto)
            store.snapshot().pushToken.takeIf { it.isNotEmpty() }?.let { runCatching { api.pushToken(it) } }
            runCatching { refreshStories() }
            for (peer in fresh.map { it.peerUin }.toSet()) ensureKnown(peer)

            // Messages that arrived while we were offline: one notification per chat.
            if (fresh.isNotEmpty() && !AppForeground.isForeground()) {
                var any = false
                for ((peer, list) in fresh.groupBy { it.peerUin }) {
                    if (prefDao.get(peer)?.muted == true) continue
                    notifyAbout(list.last())
                    any = true
                }
                if (any) sound.playIncoming()
            }
        }
    }

    private suspend fun resendPending() {
        for (p in messageDao.pending()) deliver(p.clientId)
    }

    private fun preview(kind: String, text: String, attName: String?): String =
        when (MessageKind.fromWire(kind)) {
            MessageKind.TEXT, MessageKind.SYSTEM, MessageKind.BOT -> text
            MessageKind.STICKER -> "$text Стикер"
            MessageKind.IMAGE -> "📷 " + text.ifBlank { "Фото" }
            MessageKind.FILE -> "📎 " + text.ifBlank { attName ?: "Файл" }
            MessageKind.VOICE -> "🎤 Голосовое сообщение"
            MessageKind.VIDEO_NOTE -> "⏺ Видеосообщение"
            MessageKind.POLL -> "📊 $text"
            MessageKind.GIFT -> "🎁 Подарок" + (com.icqnew.app.domain.GiftCatalog.byId(attName)?.let { ": ${it.first} ${it.second}" } ?: "")
        }

    private companion object {
        const val KEY_REV = "rev"
        const val SAVED_NAME = "Избранное"
    }
}

internal fun UserDto.toEntity() =
    ContactEntity(uin, name, avatarUrl, online, lastSeen, about, username = username, verified = verified, premium = premium)

internal fun ContactEntity.toDomain() =
    User(
        uin, name, avatarUrl, online, lastSeen, about, isGroup = isGroup, membersCount = members, ownerUin = owner,
        isChannel = kind == "channel", isPublic = isPublic, username = username, verified = verified,
        premium = premium,
    )

internal fun MessageEntity.toDomain(me: Long) = ChatMessage(
    id = id,
    serverId = serverId,
    peerUin = peerUin,
    fromUin = if (fromUin == 0L && fromMe) me else if (fromUin == 0L) peerUin else fromUin,
    fromMe = fromMe,
    text = text,
    ts = ts,
    status = when (status) {
        0 -> MessageStatus.SENDING
        2 -> MessageStatus.READ
        3 -> MessageStatus.FAILED
        else -> MessageStatus.SENT
    },
    kind = MessageKind.fromWire(kind),
    attId = attId,
    attName = attName,
    attSize = attSize,
    attMime = attMime,
    attDurMs = attDur,
    localPath = localPath,
    replyTo = replyTo,
    edited = edited,
    deleted = deleted,
    fwdName = fwdName,
    reactions = decodeReactions(reactions, me),
    failReason = failReason,
    views = views,
    storyAtt = storyAtt,
    isRead = isRead,
    played = played,
    poll = poll?.let { decodePollDto(it) }?.let { p ->
        com.icqnew.app.domain.Poll(
            p.options.map { com.icqnew.app.domain.PollOption(it.text, it.votes) },
            p.voters, p.multiple, p.anonymous, p.closed, p.my ?: emptyList(),
        )
    },
)

internal val pollJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodePollDto(raw: String): com.icqnew.app.data.remote.PollDto? =
    runCatching { pollJson.decodeFromString(com.icqnew.app.data.remote.PollDto.serializer(), raw) }.getOrNull()

private fun decodeReactions(raw: String, me: Long): List<Reaction> {
    if (raw.isBlank()) return emptyList()
    val pairs = raw.split(';').mapNotNull { part ->
        val i = part.indexOf(':')
        if (i <= 0) null else (part.substring(0, i).toLongOrNull() ?: return@mapNotNull null) to part.substring(i + 1)
    }
    return pairs.groupBy { it.second }.map { (emoji, list) ->
        Reaction(emoji, list.size, list.any { it.first == me })
    }
}
