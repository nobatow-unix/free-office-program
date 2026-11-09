package com.icqnew.app.data.remote

import com.icqnew.app.data.local.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import io.ktor.util.cio.readChannel

class ApiException(message: String, val code: Int = 0) : Exception(message)

/** "Samsung SM-G973F" — shown in the list of logged-in devices. */
fun deviceName(): String {
    val maker = android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val model = android.os.Build.MODEL.orEmpty()
    return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().take(60)
}

/** Thin REST client over Ktor. Server URL and token come from [SessionStore]. */
class IcqApi(private val client: HttpClient, private val store: SessionStore) {

    suspend fun requestCode(phone: String): OkResponse = post("/api/auth/request", CodeRequest(phone))

    suspend fun verify(phone: String, code: String, name: String?): VerifyResponse =
        post("/api/auth/verify", VerifyRequest(phone, code, name, deviceName()))

    suspend fun me(): UserDto = get("/api/me")

    suspend fun updateProfile(name: String, about: String?): UserDto = post("/api/me", ProfileRequest(name, about))

    suspend fun updateProfileExtra(name: String, birthday: String?, channelId: Long?, color: Int?): UserDto =
        post("/api/me", ProfileRequest(name, null, birthday, channelId, color))

    suspend fun getTtl(to: Long?, group: Long?): TtlDto =
        get("/api/chats/ttl", buildMap { to?.let { put("to", it.toString()) }; group?.let { put("group", it.toString()) } })

    suspend fun setTtl(to: Long?, group: Long?, seconds: Int): TtlDto = post("/api/chats/ttl", TtlRequest(to, group, seconds))

    suspend fun preview(url: String): LinkPreviewDto = get("/api/preview", mapOf("url" to url))

    suspend fun lookup(phone: String): LookupDto = get("/api/lookup", mapOf("phone" to phone))

    suspend fun approveQr(code: String): OkResponse = post("/api/qr/approve", QrApproveRequest(code))

    suspend fun appVersion(): AppVersionDto = get("/api/app/version")

    suspend fun storyComments(id: Long): List<StoryCommentDto> = get("/api/stories/$id/comments")

    suspend fun addStoryComment(id: Long, text: String): StoryCommentDto =
        post("/api/stories/$id/comments", StoryCommentRequest(text))

    suspend fun deleteStoryComment(id: Long, cid: Long) {
        val s = store.snapshot()
        val resp = client.delete(s.serverUrl + "/api/stories/$id/comments/$cid") { s.token?.let { bearerAuth(it) } }
        if (!resp.status.isSuccess()) throw ApiException(errorText(resp), resp.status.value)
    }

    /** Downloads the new APK with progress (0..1). */
    suspend fun downloadUpdate(path: String, target: File, onProgress: (Float) -> Unit) {
        val s = store.snapshot()
        target.parentFile?.mkdirs()
        client.prepareGet(s.serverUrl + path).execute { resp ->
            if (!resp.status.isSuccess()) throw ApiException(errorText(resp), resp.status.value)
            val total = resp.headers["Content-Length"]?.toLongOrNull() ?: 0L
            val ch = resp.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            var done = 0L
            target.outputStream().buffered().use { out ->
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) {
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
    }

    suspend fun resolve(username: String): ResolveDto = get("/api/resolve/" + java.net.URLEncoder.encode(username, "UTF-8"))

    suspend fun setPrivacy(searchable: Boolean, showOnline: Boolean, avatarPrivacy: String? = null, phonePrivacy: String? = null): UserDto =
        post("/api/me/privacy", PrivacyRequest(searchable, showOnline, avatarPrivacy, phonePrivacy))

    suspend fun geo(sim: String): GeoDto = get("/api/geo", mapOf("sim" to sim))

    suspend fun incomingContacts(): List<UserDto> = get("/api/contacts/incoming")

    suspend fun addContactBy(req: AddContactRequest): UserDto = post("/api/contacts", req)

    suspend fun reactStory(id: Long, emoji: String): OkResponse = post("/api/stories/$id/react", StoryReactRequest(emoji))

    suspend fun user(uin: Long): UserDto = get("/api/users/$uin")

    suspend fun search(q: String): List<UserDto> = get("/api/search", mapOf("q" to q))

    suspend fun contacts(): List<UserDto> = get("/api/contacts")

    suspend fun addContact(uin: Long): UserDto = post("/api/contacts", AddContactRequest(uin = uin))

    suspend fun syncContacts(keys: List<String>): List<UserDto> =
        post("/api/contacts/sync", SyncContactsRequest(keys))

    suspend fun updates(rev: Long): UpdatesResponse =
        get("/api/updates", mapOf("rev" to rev.toString(), "limit" to "500"))

    suspend fun blocks(): List<Long> = get("/api/blocks")

    suspend fun setBlock(uin: Long, block: Boolean): OkResponse = post("/api/blocks", BlockRequest(uin, block))

    suspend fun groups(): List<GroupDto> = get("/api/groups")

    suspend fun group(gid: Long): GroupDto = get("/api/groups/$gid")

    suspend fun createGroup(name: String, members: List<Long>, kind: String, about: String, public: Boolean): GroupDto =
        post("/api/groups", GroupCreateRequest(name, members, kind, about, public))

    suspend fun editGroup(gid: Long, name: String, about: String?, public: Boolean?, username: String? = null): GroupDto =
        post("/api/groups/$gid", GroupEditRequest(name, about, public, username))

    suspend fun groupSettings(gid: Long, settings: GroupSettingsDto, botName: String?): GroupDto =
        post("/api/groups/$gid/settings", GroupSettingsRequest(settings, botName))

    suspend fun botAvatar(gid: Long, jpeg: ByteArray): GroupDto = postJpeg("/api/groups/$gid/bot_avatar", jpeg)

    suspend fun setUsername(username: String): UserDto = post("/api/me/username", UsernameRequest(username))

    suspend fun checkUsername(u: String): CheckResponse = get("/api/username/check", mapOf("u" to u))

    suspend fun sessions(): List<SessionDto> = get("/api/sessions")

    suspend fun stories(): List<StoryGroupDto> = get("/api/stories")

    suspend fun addStory(attId: String, text: String): StoryDto = post("/api/stories", StoryRequest(attId, text))

    suspend fun viewStory(id: Long): OkResponse = post("/api/stories/$id/view", OkResponse())

    suspend fun storyViewers(id: Long): List<ViewerDto> = get("/api/stories/$id/viewers")

    suspend fun removeContact(uin: Long) {
        val s = store.snapshot()
        val resp = client.delete(s.serverUrl + "/api/contacts/$uin") { s.token?.let { bearerAuth(it) } }
        if (!resp.status.isSuccess()) throw ApiException(errorText(resp), resp.status.value)
    }

    suspend fun deleteStory(id: Long) {
        val s = store.snapshot()
        client.delete(s.serverUrl + "/api/stories/$id") { s.token?.let { bearerAuth(it) } }
    }

    suspend fun clearChat(to: Long?, group: Long?, forAll: Boolean): OkResponse =
        post("/api/chats/clear", ClearRequest(to, group, forAll))

    suspend fun endSession(id: String) {
        val s = store.snapshot()
        val resp = client.delete(s.serverUrl + "/api/sessions/$id") { s.token?.let { bearerAuth(it) } }
        if (!resp.status.isSuccess()) throw ApiException(errorText(resp), resp.status.value)
    }

    suspend fun moderate(gid: Long, uin: Long, action: String, seconds: Long): GroupDto =
        post("/api/groups/$gid/moderate", ModerateRequest(uin, action, seconds))

    suspend fun searchChats(q: String): List<GroupDto> = get("/api/search/chats", mapOf("q" to q))

    suspend fun postViews(ids: List<Long>): Map<String, Int> = post("/api/messages/views", ViewsRequest(ids))

    suspend fun votePoll(mid: Long, options: List<Int>): MessageDto = post("/api/polls/$mid/vote", VoteRequest(options))

    suspend fun pollVoters(mid: Long): List<PollVoterDto> = get("/api/polls/$mid/voters")

    suspend fun publicPosts(gid: Long): List<MessageDto> = get("/api/groups/$gid/posts", mapOf("limit" to "5"))

    suspend fun groupLog(gid: Long): List<GroupLogDto> = get("/api/groups/$gid/log")

    suspend fun groupStats(gid: Long, days: Int): GroupStatsDto = get("/api/groups/$gid/stats", mapOf("days" to days.toString()))

    suspend fun joinGroup(gid: Long): GroupDto = post("/api/groups/$gid/join", OkResponse())

    suspend fun dropPushToken(token: String) {
        val s = store.snapshot()
        client.delete(s.serverUrl + "/api/push/token") {
            s.token?.let { bearerAuth(it) }
            parameter("token", token)
        }
    }

    suspend fun pushToken(token: String): OkResponse = post("/api/push/token", PushTokenRequest(token))

    suspend fun iceServers(): kotlinx.serialization.json.JsonArray = get("/api/ice")

    suspend fun addMembers(gid: Long, uins: List<Long>): GroupDto =
        post("/api/groups/$gid/members", GroupAddRequest(uins))

    suspend fun removeMember(gid: Long, uin: Long) {
        val s = store.snapshot()
        val resp = client.delete(s.serverUrl + "/api/groups/$gid/members/$uin") { s.token?.let { bearerAuth(it) } }
        if (!resp.status.isSuccess()) throw ApiException(errorText(resp), resp.status.value)
    }

    suspend fun groupAvatar(gid: Long, jpeg: ByteArray): GroupDto = postJpeg("/api/groups/$gid/avatar", jpeg)

    suspend fun readMarks(): List<ReadMarkDto> = get("/api/read_marks")

    suspend fun logout() {
        val s = store.snapshot()
        client.post(s.serverUrl + "/api/logout") { s.token?.let { bearerAuth(it) } }
    }

    suspend fun deleteAccount() {
        val s = store.snapshot()
        val resp = client.delete(s.serverUrl + "/api/me") { s.token?.let { bearerAuth(it) } }
        if (!resp.status.isSuccess()) throw ApiException(errorText(resp), resp.status.value)
    }

    suspend fun uploadAvatar(jpeg: ByteArray): UserDto = postJpeg("/api/me/avatar", jpeg)

    private suspend inline fun <reified T> postJpeg(path: String, jpeg: ByteArray): T {
        val s = store.snapshot()
        val resp = client.post(s.serverUrl + path) {
            s.token?.let { bearerAuth(it) }
            contentType(ContentType.Image.JPEG)
            setBody(jpeg)
        }
        return unwrap(resp)
    }

    /** Uploads a file (image / document / voice) and returns its server id. */
    suspend fun upload(file: File, name: String, mime: String, durMs: Long): UploadResponse {
        val s = store.snapshot()
        val resp = client.post(s.serverUrl + "/api/upload") {
            s.token?.let { bearerAuth(it) }
            parameter("name", name)
            parameter("mime", mime)
            parameter("dur", durMs.toString())
            // streamed from disk: files up to 4 GB never sit in memory
            setBody(FileBody(file))
        }
        return unwrap(resp)
    }

    /** Downloads an attachment (authenticated) into [target]. */
    suspend fun download(attId: String, target: File) {
        val s = store.snapshot()
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        client.prepareGet(s.serverUrl + "/api/files/" + attId) { s.token?.let { bearerAuth(it) } }.execute { resp ->
            if (!resp.status.isSuccess()) throw ApiException(errorText(resp), resp.status.value)
            val ch = resp.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            part.outputStream().buffered().use { out ->
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) out.write(buf, 0, n)
                }
            }
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    /** Direct URL + auth header of a file (for streaming video / audio). */
    suspend fun fileUrl(attId: String): Pair<String, String?> {
        val s = store.snapshot()
        return (s.serverUrl + "/api/files/" + attId) to s.token?.let { "Bearer $it" }
    }

    private suspend inline fun <reified T> get(path: String, params: Map<String, String> = emptyMap()): T {
        val s = store.snapshot()
        val resp = client.get(s.serverUrl + path) {
            s.token?.let { bearerAuth(it) }
            params.forEach { (k, v) -> parameter(k, v) }
        }
        return unwrap(resp)
    }

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T {
        val s = store.snapshot()
        val resp = client.post(s.serverUrl + path) {
            s.token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return unwrap(resp)
    }

    private suspend inline fun <reified T> unwrap(resp: HttpResponse): T {
        if (!resp.status.isSuccess()) throw ApiException(errorText(resp), resp.status.value)
        return resp.body()
    }

    private suspend fun errorText(resp: HttpResponse): String {
        val detail = runCatching {
            Json.parseToJsonElement(resp.bodyAsText()).jsonObject["detail"]?.jsonPrimitive?.content
        }.getOrNull()
        return detail ?: "Ошибка сервера (${resp.status.value})"
    }
}


/** Streams a file from disk as the request body (big uploads never sit in memory). */
private class FileBody(private val file: File) : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
    override val contentLength: Long get() = file.length()
    override val contentType: ContentType get() = ContentType.Application.OctetStream
    override fun readFrom(): io.ktor.utils.io.ByteReadChannel = file.readChannel()
}
