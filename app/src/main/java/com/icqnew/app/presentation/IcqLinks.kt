package com.icqnew.app.presentation

/** Links to people / groups / channels inside ICQ New. */
object IcqLinks {
    const val HOST = "icq-new.online"
    /** the website with the download button */
    const val SITE = "https://icq-new.ru"
    private val HOSTS = setOf(HOST, "www.$HOST", "icq-new.ru", "www.icq-new.ru")

    sealed interface Target {
        data class User(val uin: Long) : Target
        data class Group(val gid: Long) : Target
        data class Name(val username: String) : Target
    }

    fun user(username: String) = "https://$HOST/@$username"
    fun group(gid: Long, username: String?) = if (!username.isNullOrBlank()) "https://$HOST/@$username" else "https://$HOST/g/$gid"

    /** icqnew://open?u=1 · icqnew://open?g=2 · https://icq-new.online/@name · https://icq-new.online/g/2 */
    fun parse(raw: String): Target? {
        val url = raw.trim()
        if (url.startsWith("icqnew://", ignoreCase = true)) {
            val q = url.substringAfter('?', "")
            val params = q.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            params["u"]?.toLongOrNull()?.let { return Target.User(it) }
            params["g"]?.toLongOrNull()?.let { return Target.Group(it) }
            return null
        }
        val m = Regex("^(?:https?://)?([A-Za-z0-9.-]+)(?::\\d+)?/(?:@([A-Za-z0-9_]{3,32})|g/(\\d+))/?$").find(url) ?: return null
        if (m.groupValues[1].lowercase() !in HOSTS) return null
        if (m.groupValues[2].isNotEmpty()) return Target.Name(m.groupValues[2])
        return m.groupValues[3].toLongOrNull()?.let { Target.Group(it) }
    }
}
