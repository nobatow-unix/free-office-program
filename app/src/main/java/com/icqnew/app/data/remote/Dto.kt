package com.icqnew.app.data.remote

import com.icqnew.app.domain.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val uin: Long,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val about: String = "",
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long = 0,
    val searchable: Boolean = true,
    @SerialName("show_online") val showOnline: Boolean = true,
    val phone: String = "",
    val role: String = "",
    @SerialName("muted_until") val mutedUntil: Long = 0,
    val username: String? = null,
    val verified: Boolean = false,
    val bot: Boolean = false,
    val contact: Boolean = false,
    @SerialName("added_me") val addedMe: Boolean = false,
    val mutual: Boolean = false,
    val alias: String? = null,
    @SerialName("added_at") val addedAt: Long = 0,
    @SerialName("avatar_privacy") val avatarPrivacy: String = "all",
    val birthday: String = "",
    val color: Int = 0,
    val channel: ChannelRefDto? = null,
    @SerialName("phone_privacy") val phonePrivacy: String = "contacts",
)

@Serializable
data class GroupDto(
    val id: Long,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val owner: Long = 0,
    val created: Long = 0,
    val count: Int = 0,
    val members: List<UserDto> = emptyList(),
    val kind: String = "group",
    val about: String = "",
    val public: Boolean = false,
    val member: Boolean = true,
    val username: String? = null,
    val verified: Boolean = false,
    val settings: GroupSettingsDto = GroupSettingsDto(),
    @SerialName("bot_name") val botName: String = "ICQ Бот",
    @SerialName("bot_avatar") val botAvatar: String? = null,
)

@Serializable
data class GroupSettingsDto(
    @SerialName("only_admins_add") val onlyAdminsAdd: Boolean = false,
    @SerialName("slow_mode") val slowMode: Int = 0,
    @SerialName("members_media") val membersMedia: Boolean = true,
    val reactions: Boolean = true,
    val welcome: Boolean = true,
    @SerialName("help_on_open") val helpOnOpen: Boolean = true,
    val signatures: Boolean = false,
)

@Serializable
data class GroupSettingsRequest(val settings: GroupSettingsDto, @SerialName("bot_name") val botName: String? = null)

@Serializable
data class UsernameRequest(val username: String)

@Serializable
data class CheckResponse(val ok: Boolean = false, val text: String = "")

@Serializable
data class SessionDto(
    val id: String,
    val device: String = "",
    val created: Long = 0,
    @SerialName("last_used") val lastUsed: Long = 0,
    val current: Boolean = false,
    val online: Boolean = false,
)

@Serializable
data class OkResponse(val ok: Boolean = true, @SerialName("retry_in") val retryIn: Int = 30, @SerialName("in_app") val inApp: Boolean = false)

@Serializable
data class CodeRequest(val phone: String)

@Serializable
data class VerifyRequest(val phone: String, val code: String, val name: String? = null, val device: String? = null)

@Serializable
data class VerifyResponse(
    val token: String? = null,
    val user: UserDto? = null,
    @SerialName("need_name") val needName: Boolean = false,
)

@Serializable
data class AddContactRequest(
    val uin: Long? = null,
    val phone: String? = null,
    val query: String? = null,
    val name: String? = null,
    @SerialName("share_phone") val sharePhone: Boolean = false,
)

@Serializable
data class GeoDto(val country: String = "", val dial: String = "", val source: String = "")

@Serializable
data class StoryReactRequest(val emoji: String)

@Serializable
data class SyncContactsRequest(val keys: List<String>)

@Serializable
data class ProfileRequest(
    val name: String,
    val about: String? = null,
    val birthday: String? = null,
    @SerialName("channel_id") val channelId: Long? = null,
    val color: Int? = null,
)

@Serializable
data class ChannelRefDto(val id: Long, val name: String = "", @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
data class TtlDto(val seconds: Int = 0)

@Serializable
data class TtlRequest(val to: Long? = null, val group: Long? = null, val seconds: Int = 0)

@Serializable
data class LinkPreviewDto(
    val url: String = "",
    val title: String? = null,
    val description: String? = null,
    val image: String? = null,
    val site: String? = null,
    /** links inside ICQ: "user" / "group" / "channel" / "missing" */
    val type: String = "",
    val peer: Long = 0,
    val count: Int = 0,
    val verified: Boolean = false,
)

@Serializable
data class LookupDto(val found: Boolean = false, val user: UserDto? = null)

@Serializable
data class QrApproveRequest(val code: String)

@Serializable
data class DefaultTtlRequest(val seconds: Int)

@Serializable
data class AppVersionDto(val code: Long = 0, val name: String = "", val notes: String = "", val url: String = "/download", val size: Long = 0, val sha256: String = "")

@Serializable
data class ResolveGroupDto(val id: Long)

@Serializable
data class ResolveDto(val type: String = "", val user: UserDto? = null, val group: ResolveGroupDto? = null)

@Serializable
data class PrivacyRequest(
    val searchable: Boolean,
    @SerialName("show_online") val showOnline: Boolean,
    @SerialName("avatar_privacy") val avatarPrivacy: String? = null,
    @SerialName("phone_privacy") val phonePrivacy: String? = null,
)

@Serializable
data class BlockRequest(val uin: Long, val block: Boolean)

@Serializable
data class GroupCreateRequest(
    val name: String,
    val members: List<Long>,
    val kind: String = "group",
    val about: String = "",
    val public: Boolean = false,
)

@Serializable
data class GroupEditRequest(
    val name: String,
    val about: String? = null,
    val public: Boolean? = null,
    val username: String? = null,
)

@Serializable
data class ModerateRequest(val uin: Long, val action: String, val seconds: Long = 0)

@Serializable
data class PushTokenRequest(val token: String)

@Serializable
data class GroupAddRequest(val members: List<Long>)

@Serializable
data class UploadResponse(
    val id: String,
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val dur: Long = 0,
)

@Serializable
data class ReadMarkDto(val peer: Long, val upto: Long)

@Serializable
data class ReactionDto(val uin: Long, val emoji: String)

@Serializable
data class MessageDto(
    val id: Long,
    @SerialName("client_id") val clientId: String,
    @SerialName("from_uin") val fromUin: Long,
    @SerialName("to_uin") val toUin: Long = 0,
    @SerialName("group_id") val groupId: Long? = null,
    val text: String = "",
    val ts: Long,
    val read: Boolean = false,
    val kind: String = "text",
    @SerialName("att_id") val attId: String? = null,
    @SerialName("att_name") val attName: String? = null,
    @SerialName("att_size") val attSize: Long = 0,
    @SerialName("att_mime") val attMime: String? = null,
    @SerialName("att_dur") val attDur: Long = 0,
    @SerialName("reply_to") val replyTo: Long? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    @SerialName("fwd_name") val fwdName: String? = null,
    val rev: Long = 0,
    val reactions: List<ReactionDto> = emptyList(),
    val views: Int = 0,
    val poll: PollDto? = null,
)

@Serializable
data class PollOptionDto(val text: String, val votes: Int = 0)

@Serializable
data class PollDto(
    val options: List<PollOptionDto> = emptyList(),
    val voters: Int = 0,
    val multiple: Boolean = false,
    val anonymous: Boolean = true,
    val closed: Boolean = false,
    /** my choice; null = unknown (broadcast updates do not carry it) */
    val my: List<Int>? = null,
)

/** Poll settings sent together with a new "poll" message. */
@Serializable
data class WsPoll(val options: List<String>, val multiple: Boolean = false, val anonymous: Boolean = true)

@Serializable
data class ViewsRequest(val ids: List<Long>)

@Serializable
data class VoteRequest(val options: List<Int>)

@Serializable
data class PollVoterDto(val opt: Int, val user: UserDto)

@Serializable
data class GroupLogDto(val action: String, val ts: Long, val user: UserDto, val actor: UserDto? = null)

@Serializable
data class StatDayDto(val day: Long, val joined: Int = 0, val left: Int = 0, val views: Int = 0)

@Serializable
data class StatPostDto(val id: Long, val text: String = "", val kind: String = "text", val ts: Long = 0,
                       val views: Int = 0, val reactions: Int = 0)

@Serializable
data class StatReactionDto(val emoji: String, val count: Int)

@Serializable
data class GroupStatsDto(
    val members: Int = 0, val views: Int = 0, val reactions: Int = 0,
    @SerialName("avg_views") val avgViews: Int = 0,
    val joined: Int = 0, val left: Int = 0,
    val series: List<StatDayDto> = emptyList(),
    val posts: List<StatPostDto> = emptyList(),
    @SerialName("top_reactions") val topReactions: List<StatReactionDto> = emptyList(),
)

@Serializable
data class UpdatesResponse(
    val messages: List<MessageDto> = emptyList(),
    val rev: Long = 0,
    val more: Boolean = false,
    val clears: List<ReadMarkDto> = emptyList(),
)

@Serializable
data class StoryDto(
    val id: Long,
    val uin: Long,
    @SerialName("att_id") val attId: String,
    val text: String = "",
    val ts: Long = 0,
    val seen: Boolean = false,
    val views: Int = 0,
    @SerialName("my_reaction") val myReaction: String = "",
    val reactions: Int = 0,
    val comments: Int = 0,
)

@Serializable
data class StoryCommentDto(val id: Long, val text: String, val ts: Long, val user: UserDto)

@Serializable
data class StoryCommentRequest(val text: String)

@Serializable
data class StoryGroupDto(
    val user: UserDto,
    val stories: List<StoryDto> = emptyList(),
    @SerialName("all_seen") val allSeen: Boolean = false,
)

@Serializable
data class StoryRequest(@SerialName("att_id") val attId: String, val text: String)

@Serializable
data class ViewerDto(
    val uin: Long,
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val verified: Boolean = false,
    val viewed: Long = 0,
    val reaction: String = "",
)

@Serializable
data class ClearRequest(val to: Long? = null, val group: Long? = null, @SerialName("for_all") val forAll: Boolean = true)

/** Client -> server over WebSocket. Null fields are not sent (Json explicitNulls = false). */
@Serializable
data class WsOut(
    val type: String,
    val to: Long? = null,
    val group: Long? = null,
    val text: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    val kind: String? = null,
    @SerialName("att_id") val attId: String? = null,
    @SerialName("reply_to") val replyTo: Long? = null,
    @SerialName("fwd_name") val fwdName: String? = null,
    val id: Long? = null,
    val emoji: String? = null,
    val data: kotlinx.serialization.json.JsonObject? = null,
    val active: Boolean? = null,
    val poll: WsPoll? = null,
)

/** Server -> client over WebSocket
 *  (message / update / ack / failed / presence / typing / read / profile / gone / group / group_gone). */
@Serializable
data class WsIn(
    val type: String,
    val message: MessageDto? = null,
    @SerialName("client_id") val clientId: String? = null,
    val id: Long? = null,
    val ts: Long? = null,
    val uin: Long? = null,
    val gid: Long? = null,
    val online: Boolean? = null,
    @SerialName("last_seen") val lastSeen: Long? = null,
    val upto: Long? = null,
    val user: UserDto? = null,
    val group: GroupDto? = null,
    val text: String? = null,
    val from: Long? = null,
    val data: kotlinx.serialization.json.JsonObject? = null,
)

fun UserDto.toDomain() = User(
    uin, name, avatarUrl, online, lastSeen, about, username = username, verified = verified,
    birthday = birthday, color = color, channelId = channel?.id ?: 0, channelName = channel?.name.orEmpty(),
    channelAvatar = channel?.avatarUrl,
)
