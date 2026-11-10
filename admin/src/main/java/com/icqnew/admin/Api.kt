package com.icqnew.admin

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

// ------------------------------------------------------------------ data
@Serializable
data class AdminDto(
    val id: Long = 0,
    val login: String = "",
    val role: String = "admin",
    val perms: List<String> = emptyList(),
    val enabled: Boolean = true,
    val created: Long = 0,
    @SerialName("last_seen") val lastSeen: Long = 0,
    val device: String = "",
    @SerialName("auto_sms") val autoSms: Boolean = false,
    @SerialName("sms_sent") val smsSent: Int = 0,
) {
    val isSuper: Boolean get() = role == "super"
}

@Serializable
data class LoginResp(val token: String, val admin: AdminDto, @SerialName("perm_names") val permNames: Map<String, String> = emptyMap())

@Serializable
data class AutoDevice(val login: String, val device: String = "")

@Serializable
data class MeResp(
    val admin: AdminDto,
    @SerialName("perm_names") val permNames: Map<String, String> = emptyMap(),
    val users: Int = 0,
    @SerialName("waiting_sms") val waitingSms: Int = 0,
    val online: Int = 0,
    @SerialName("auto_sms") val autoSms: List<AutoDevice> = emptyList(),
)

@Serializable
data class CodeItem(
    val phone: String,
    val code: String,
    val age: Long = 0,
    val expired: Boolean = false,
    val sent: Boolean = false,
    val email: Boolean = false,
    val by: String = "",
    val error: String = "",
    val sending: Boolean = false,
)

@Serializable
data class Pending(
    val items: List<CodeItem> = emptyList(),
    val users: Int = 0,
    val messages: Int = 0,
    val groups: Int = 0,
    val channels: Int = 0,
    val online: Int = 0,
)

@Serializable
data class SmsJob(val phone: String, val code: String, val text: String, val age: Long = 0)

@Serializable
data class UserRow(
    val uin: Long,
    val name: String,
    val phone: String = "",
    val online: Boolean = false,
    @SerialName("last_seen") val lastSeen: Long = 0,
    val created: Long = 0,
    val banned: Boolean = false,
    val verified: Boolean = false,
    val username: String? = null,
    val messages: Int = 0,
)

@Serializable
data class GroupRow(
    val id: Long,
    val name: String,
    val kind: String = "group",
    val count: Int = 0,
    val public: Boolean = false,
    val username: String? = null,
    val verified: Boolean = false,
    val owner: Long = 0,
)

@Serializable
data class LotRow(val username: String, val price: Int, @SerialName("sold_to") val soldTo: Long? = null, val buyer: String? = null)

@Serializable
data class NodeRow(val id: String, val url: String = "", val alive: Boolean = false, val me: Boolean = false, val online: Int = 0)

@Serializable
data class ClusterResp(
    val enabled: Boolean = false,
    val leader: String? = null,
    val db: String = "",
    val nodes: List<NodeRow> = emptyList(),
    val online: Int = 0,
)

@Serializable
data class LogRow(val login: String, val action: String, val ts: Long)

@Serializable
data class BalanceRow(val uin: Long = 0, val name: String? = null, val delta: Int = 0, val reason: String = "", val ts: Long = 0)

class ApiError(msg: String, val code: Int = 0) : Exception(msg)

// ------------------------------------------------------------------ settings on this phone
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("admin", Context.MODE_PRIVATE)
    var server: String
        get() = p.getString("server", "https://icq-new.online") ?: "https://icq-new.online"
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    var login: String
        get() = p.getString("login", "") ?: ""
        set(v) = p.edit().putString("login", v).apply()
    var role: String
        get() = p.getString("role", "admin") ?: "admin"
        set(v) = p.edit().putString("role", v).apply()
    var perms: Set<String>
        get() = p.getStringSet("perms", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("perms", v).apply()
    /** automatic SMS from this phone is on */
    var autoSms: Boolean
        get() = p.getBoolean("auto", false)
        set(v) = p.edit().putBoolean("auto", v).apply()
    /** SIM card for the SMS (subscription id, -1 = default) */
    var simId: Int
        get() = p.getInt("sim", -1)
        set(v) = p.edit().putInt("sim", v).apply()
    var sentToday: Int
        get() = if (p.getLong("day", 0) == today()) p.getInt("sent", 0) else 0
        set(v) = p.edit().putLong("day", today()).putInt("sent", v).apply()

    private fun today() = System.currentTimeMillis() / 86_400_000
}

// ------------------------------------------------------------------ server calls
class Api(private val prefs: Prefs) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        engine {
            config {
                connectTimeout(8, TimeUnit.SECONDS)
                readTimeout(70, TimeUnit.SECONDS) // long-poll of /sms/next waits up to 25 s
                retryOnConnectionFailure(true)
            }
        }
        expectSuccess = false
    }

    private fun url(path: String) = prefs.server + path
    private fun HttpRequestBuilder.auth() { prefs.token?.let { bearerAuth(it) } }

    private suspend fun check(r: HttpResponse): HttpResponse {
        if (r.status.isSuccess()) return r
        val text = runCatching { r.bodyAsText() }.getOrDefault("")
        val detail = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?.get("detail")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        throw ApiError(detail ?: "Ошибка ${r.status.value}", r.status.value)
    }

    suspend fun login(server: String, login: String, password: String, device: String): LoginResp {
        prefs.server = server
        val r = check(client.post(url("/admin/api/login")) {
            contentType(ContentType.Application.Json)
            setBody(mapOf("login" to login, "password" to password, "device" to device))
        })
        return r.body()
    }

    suspend fun logout() { runCatching { client.post(url("/admin/api/logout")) { auth() } } }

    suspend fun me(): MeResp = check(client.get(url("/admin/api/me")) { auth() }).body()

    // codes / SMS
    suspend fun pending(): Pending = check(client.get(url("/admin/api/pending")) { auth() }).body()
    suspend fun markSent(phone: String) { check(client.post(url("/admin/api/sent/" + phone.filter { it.isDigit() })) { auth() }) }

    /** null = nothing to send right now */
    suspend fun nextSms(device: String): SmsJob? {
        val r = client.get(url("/admin/api/sms/next")) { auth(); parameter("wait", 25); parameter("device", device) }
        if (r.status == HttpStatusCode.NoContent) return null
        return check(r).body()
    }

    suspend fun smsResult(phone: String, code: String, ok: Boolean, error: String?) {
        check(client.post(url("/admin/api/sms/result")) {
            auth(); contentType(ContentType.Application.Json)
            setBody(SmsResultBody(phone, code, ok, error))
        })
    }

    suspend fun smsAuto(on: Boolean) {
        runCatching {
            client.post(url("/admin/api/sms/auto")) { auth(); contentType(ContentType.Application.Json); setBody(mapOf("on" to on)) }
        }
    }

    // users / groups / verify
    suspend fun users(q: String): List<UserRow> = check(client.get(url("/admin/api/users")) { auth(); parameter("q", q) }).body()
    suspend fun ban(uin: Long, on: Boolean) { check(client.post(url("/admin/api/users/$uin/ban")) { auth(); parameter("ban", on) }) }
    suspend fun deleteUser(uin: Long) { check(client.delete(url("/admin/api/users/$uin")) { auth() }) }
    suspend fun groups(q: String): List<GroupRow> = check(client.get(url("/admin/api/groups")) { auth(); parameter("q", q) }).body()
    suspend fun verify(kind: String, id: Long, on: Boolean) {
        check(client.post(url("/admin/api/verify")) {
            auth(); contentType(ContentType.Application.Json); setBody(VerifyBody(kind, id, on))
        })
    }

    // balance
    suspend fun topUp(who: String, amount: Int, reason: String): String {
        val r = check(client.post(url("/admin/api/balance")) {
            auth(); contentType(ContentType.Application.Json); setBody(BalanceBody(who, amount, reason))
        })
        return r.bodyAsText()
    }

    suspend fun balanceLog(): List<BalanceRow> =
        runCatching { check(client.get(url("/admin/api/balance")) { auth() }).body<List<BalanceRow>>() }.getOrDefault(emptyList())

    // fragment
    suspend fun lots(): List<LotRow> = check(client.get(url("/admin/api/fragment")) { auth() }).body()
    suspend fun setLot(username: String, price: Int) {
        check(client.post(url("/admin/api/fragment")) {
            auth(); contentType(ContentType.Application.Json); setBody(LotBody(username, price))
        })
    }

    // servers
    suspend fun cluster(): ClusterResp = check(client.get(url("/admin/api/cluster")) { auth() }).body()

    // admins (super)
    suspend fun admins(): List<AdminDto> = check(client.get(url("/admin/api/admins")) { auth() }).body()
    suspend fun createAdmin(b: AdminBody): AdminDto = check(client.post(url("/admin/api/admins")) {
        auth(); contentType(ContentType.Application.Json); setBody(b)
    }).body()
    suspend fun editAdmin(id: Long, b: AdminBody): AdminDto = check(client.patch(url("/admin/api/admins/$id")) {
        auth(); contentType(ContentType.Application.Json); setBody(b)
    }).body()
    suspend fun deleteAdmin(id: Long) { check(client.delete(url("/admin/api/admins/$id")) { auth() }) }
    suspend fun log(): List<LogRow> = check(client.get(url("/admin/api/log")) { auth() }).body()
}

@Serializable
data class SmsResultBody(val phone: String, val code: String, val ok: Boolean, val error: String? = null)

@Serializable
data class VerifyBody(val kind: String, val id: Long, val on: Boolean)

@Serializable
data class BalanceBody(val who: String, val amount: Int, val reason: String)

@Serializable
data class LotBody(val username: String, val price: Int)

@Serializable
data class AdminBody(
    val login: String? = null,
    val password: String? = null,
    val role: String? = null,
    val perms: List<String>? = null,
    val enabled: Boolean? = null,
)

/** One API + settings for the whole app (activity and the auto-SMS service). */
object Core {
    lateinit var prefs: Prefs
    lateinit var api: Api
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = Prefs(ctx.applicationContext)
        api = Api(prefs)
    }
}

val PERM_NAMES = linkedMapOf(
    "sms" to "Коды и SMS",
    "users" to "Пользователи",
    "verify" to "Галочки",
    "groups" to "Группы и каналы",
    "balance" to "Баланс",
    "fragment" to "Fragment",
    "servers" to "Серверы",
)
