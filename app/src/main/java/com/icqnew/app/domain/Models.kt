package com.icqnew.app.domain

/**
 * Chat partner as the UI sees it. A group chat is a "peer" too: its key is the NEGATIVE
 * group id (peer = -gid), so one chat list / chat screen works for both.
 */
data class User(
    val uin: Long,
    val name: String,
    val avatarUrl: String?,
    val online: Boolean,
    val lastSeen: Long,
    val about: String = "",
    val isGroup: Boolean = false,
    val membersCount: Int = 0,
    val ownerUin: Long = 0,
    /** group / channel members: owner / admin / member */
    val role: String = "",
    /** 0 = can write, -1 = muted forever, otherwise epoch ms */
    val mutedUntil: Long = 0,
    val isChannel: Boolean = false,
    val isPublic: Boolean = false,
    val username: String? = null,
    val verified: Boolean = false,
    /** "DD.MM" or "DD.MM.YYYY" */
    val birthday: String = "",
    /** profile colour 0 (none) .. 8 */
    val color: Int = 0,
    val channelId: Long = 0,
    val channelName: String = "",
    val channelAvatar: String? = null,
    /** "Saved messages" — the chat with yourself */
    val isSelf: Boolean = false,
) {
    val isAdmin: Boolean get() = role == "owner" || role == "admin"
    fun isMutedNow(): Boolean = mutedUntil < 0 || mutedUntil > System.currentTimeMillis()
}

fun isGroupPeer(peer: Long): Boolean = peer < 0
fun groupIdOf(peer: Long): Long = -peer
fun groupPeer(gid: Long): Long = -gid

/** Logged-in session (token is kept in DataStore). */
data class Session(
    val token: String,
    val uin: Long,
    val name: String,
    val avatarUrl: String?,
    val about: String = "",
    val phone: String = "",
    val username: String = "",
    val verified: Boolean = false,
)

enum class MessageStatus { SENDING, SENT, READ, FAILED }

enum class MessageKind(val wire: String) {
    TEXT("text"), STICKER("sticker"), IMAGE("image"), FILE("file"), VOICE("voice"), SYSTEM("system"), BOT("bot"),
    VIDEO_NOTE("video_note"), POLL("poll");

    val isAttachment: Boolean get() = this == IMAGE || this == FILE || this == VOICE || this == VIDEO_NOTE

    companion object {
        fun fromWire(s: String?): MessageKind = entries.firstOrNull { it.wire == s } ?: TEXT
    }
}

data class Reaction(val emoji: String, val count: Int, val mine: Boolean)

data class ChatMessage(
    val id: Long,
    val serverId: Long?,
    val peerUin: Long,
    val fromUin: Long,
    val fromMe: Boolean,
    val text: String,
    val ts: Long,
    val status: MessageStatus,
    val kind: MessageKind,
    val attId: String?,
    val attName: String?,
    val attSize: Long,
    val attMime: String?,
    val attDurMs: Long,
    val localPath: String?,
    val replyTo: Long? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val fwdName: String? = null,
    val reactions: List<Reaction> = emptyList(),
    val failReason: String? = null,
    /** channel posts: how many people saw it */
    val views: Int = 0,
    val poll: Poll? = null,
)

data class PollOption(val text: String, val votes: Int)

data class Poll(
    val options: List<PollOption>,
    val voters: Int,
    val multiple: Boolean,
    val anonymous: Boolean,
    val closed: Boolean,
    val my: List<Int>,
) {
    val voted: Boolean get() = my.isNotEmpty()
}

data class GroupLogEntry(val action: String, val ts: Long, val user: User, val actor: User?)

data class StatDay(val day: Long, val joined: Int, val left: Int, val views: Int)
data class StatPost(val id: Long, val text: String, val kind: String, val ts: Long, val views: Int, val reactions: Int)

data class GroupStats(
    val members: Int, val views: Int, val reactions: Int, val avgViews: Int,
    val joined: Int, val left: Int,
    val series: List<StatDay>, val posts: List<StatPost>, val topReactions: List<Pair<String, Int>>,
)

/** One row of the chat list: peer + last message + unread counter. */
data class ChatPreview(
    val peer: User,
    val lastText: String,
    val lastTs: Long,
    val lastFromMe: Boolean,
    val unread: Int,
    val pinned: Boolean = false,
    val lastKind: MessageKind = MessageKind.TEXT,
    val lastStatus: MessageStatus = MessageStatus.SENT,
    val muted: Boolean = false,
    val lastSender: String? = null,
)

/** Local per-chat options. */
data class ChatPrefs(
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val blocked: Boolean = false,
    /** this chat's own wallpaper ("" = the common one) */
    val wallpaper: String = "",
)

data class GroupInfo(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
    val ownerUin: Long,
    val members: List<User>,
    val isChannel: Boolean = false,
    val about: String = "",
    val isPublic: Boolean = false,
    val count: Int = 0,
    /** my role in this chat: owner / admin / member */
    val myRole: String = "member",
    val myMutedUntil: Long = 0,
    val username: String? = null,
    val verified: Boolean = false,
    val settings: GroupSettings = GroupSettings(),
    val botName: String = "ICQ Бот",
    val botAvatar: String? = null,
) {
    val iAmAdmin: Boolean get() = myRole == "owner" || myRole == "admin"
    val iAmOwner: Boolean get() = myRole == "owner"
}

data class GroupSettings(
    val onlyAdminsAdd: Boolean = false,
    val slowMode: Int = 0,
    val membersMedia: Boolean = true,
    val reactions: Boolean = true,
    val welcome: Boolean = true,
    val helpOnOpen: Boolean = true,
    val signatures: Boolean = false,
)

const val BOT_UIN = 10_000_000L

/** «ICQ» service account: login codes and new-device alerts (read-only chat). */
const val SERVICE_UIN = 777_000L

data class Story(
    val id: Long,
    val uin: Long,
    val attId: String,
    val text: String,
    val ts: Long,
    val seen: Boolean,
    val views: Int,
    val myReaction: String = "",
    val reactions: Int = 0,
    val comments: Int = 0,
)

data class StoryComment(val id: Long, val text: String, val ts: Long, val user: User)

data class StoryGroup(val user: User, val stories: List<Story>, val allSeen: Boolean)

/** A device where the account is logged in. */
data class DeviceSession(
    val id: String,
    val device: String,
    val created: Long,
    val lastUsed: Long,
    val current: Boolean,
    val online: Boolean,
)

/** A public group / channel found by search. */
data class PublicChat(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
    val about: String,
    val isChannel: Boolean,
    val count: Int,
    val member: Boolean,
    val verified: Boolean = false,
    val username: String? = null,
)

/** One message found by the local message search. */
data class FoundMessage(val peer: Long, val chatName: String, val text: String, val ts: Long)

enum class CallStatus { DONE, MISSED, DECLINED, CANCELLED }

data class CallRecord(
    val id: Long,
    val peer: Long,
    val name: String,
    val avatarUrl: String?,
    val outgoing: Boolean,
    val video: Boolean,
    val ts: Long,
    val durationSec: Int,
    val status: CallStatus,
)

/** Appearance and notification options kept on this phone. */
data class AppSettings(
    /** "" = ICQ pattern, "plain", "color:<argb>", "grad:<n>", "image:<path>" */
    val wallpaper: String = "",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Int = 0,
    val fontScale: Float = 1f,
    val vibrate: Boolean = true,
    val notifyPreview: Boolean = true,
    val sound: Boolean = true,
    /** "icq" (oh-oh), "classic" (two notes), "system" (phone's notification sound), "none" */
    val soundChoice: String = "icq",
    /** what a swipe on a chat does: delete / archive / pin / mute / read */
    val swipeAction: String = "delete",
    /** archived chats (peer keys) */
    val archived: Set<Long> = emptySet(),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

sealed interface VerifyResult {
    object NeedName : VerifyResult
    data class Done(val session: Session) : VerifyResult
}

/** All user-changeable privacy options. server* ones live on the server, local* only on this phone. */
data class PrivacySettings(
    val searchable: Boolean = true,
    val showOnline: Boolean = true,
    val sendReadReceipts: Boolean = true,
    val sendTyping: Boolean = true,
    val blockScreenshots: Boolean = false,
    /** who sees my profile photo: all / contacts / nobody */
    val avatarPrivacy: String = "all",
    /** who sees my phone number: all / contacts / nobody */
    val phonePrivacy: String = "contacts",
)

data class LinkPreview(
    val url: String,
    val title: String,
    val description: String,
    val image: String?,
    val site: String,
    /** "" for web pages; "user" / "group" / "channel" for ICQ links */
    val type: String = "",
    val peer: Long = 0,
    val count: Int = 0,
    val verified: Boolean = false,
)

/** A person in my contact list (or someone who added me). */
data class ContactCard(
    val user: User,
    val phone: String,
    val alias: String?,
    val iAdded: Boolean,
    val addedMe: Boolean,
    val addedAt: Long,
) {
    val mutual: Boolean get() = iAdded && addedMe
}
