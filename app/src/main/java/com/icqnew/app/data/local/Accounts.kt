package com.icqnew.app.data.local

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Several accounts on one phone (like Telegram, up to 5).
 * The active account lives in [SessionStore]; the others are kept here with their tokens.
 * Every account has its own Room database file, so chats never mix.
 * Switching = save the current one, load the other one, restart the app process.
 */
object Accounts {
    const val MAX = 5
    private const val PREFS = "icq_accounts"
    private const val KEY_DB = "db"
    private const val KEY_SAVED = "saved"
    const val LEGACY_DB = "icq.db"

    data class Saved(
        val uin: Long,
        val token: String,
        val name: String,
        val avatarUrl: String?,
        val phone: String,
        val username: String,
        val db: String,
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Database file of the active account (read once when the app starts). */
    fun dbName(c: Context): String = prefs(c).getString(KEY_DB, LEGACY_DB) ?: LEGACY_DB

    fun saved(c: Context): List<Saved> {
        val raw = prefs(c).getString(KEY_SAVED, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Saved(
                    o.getLong("uin"), o.getString("token"), o.optString("name"),
                    o.optString("avatar").ifBlank { null }, o.optString("phone"), o.optString("username"),
                    o.optString("db", LEGACY_DB),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(c: Context, list: List<Saved>, db: String? = null) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("uin", it.uin).put("token", it.token).put("name", it.name)
                .put("avatar", it.avatarUrl ?: "").put("phone", it.phone).put("username", it.username).put("db", it.db))
        }
        prefs(c).edit().putString(KEY_SAVED, a.toString()).apply { if (db != null) putString(KEY_DB, db) }.commit()
    }

    /** Puts the active account (from the session) into the saved list. */
    /** The folders made before several accounts existed belong to the first account (the one with icq.db). */
    suspend fun fixLegacyFolders(c: Context, store: SessionStore) {
        val owner = if (dbName(c) == LEGACY_DB) store.snapshot().uin else saved(c).firstOrNull { it.db == LEGACY_DB }?.uin ?: 0L
        if (owner != 0L) store.claimFolders(owner)
    }

    suspend fun rememberCurrent(c: Context, store: SessionStore) {
        fixLegacyFolders(c, store)
        val s = store.snapshot()
        val token = s.token ?: return
        if (s.uin == 0L) return
        val me = Saved(s.uin, token, s.name, s.avatarUrl, s.phone, s.username, dbName(c))
        write(c, listOf(me) + saved(c).filter { it.uin != s.uin })
    }

    /** Other accounts (not the active one). */
    suspend fun others(c: Context, store: SessionStore): List<Saved> {
        val uin = store.snapshot().uin
        return saved(c).filter { it.uin != uin }
    }

    /** «Добавить аккаунт»: keep this one, open the login screen with a fresh database. */
    suspend fun startAdding(c: Context, store: SessionStore) {
        rememberCurrent(c, store)
        write(c, saved(c), db = "icq_" + System.currentTimeMillis() + ".db")
        store.clearSession()
        restart(c)
    }

    suspend fun switchTo(c: Context, store: SessionStore, uin: Long) {
        rememberCurrent(c, store)
        val t = saved(c).firstOrNull { it.uin == uin } ?: return
        store.saveSession(t.token, t.uin, t.name, t.avatarUrl, true, true, "", t.phone)
        store.setIdentity(t.username, false)
        write(c, saved(c), db = t.db)
        restart(c)
    }

    /** After logging out of the active account: forget it, go to the next saved one (if any). */
    suspend fun afterLogout(c: Context, store: SessionStore, uin: Long): Boolean {
        val rest = saved(c).filter { it.uin != uin }
        write(c, rest)
        val next = rest.firstOrNull() ?: return false
        store.saveSession(next.token, next.uin, next.name, next.avatarUrl, true, true, "", next.phone)
        store.setIdentity(next.username, false)
        write(c, rest, db = next.db)
        restart(c)
        return true
    }

    /** Restarts the process so every singleton (database, socket) is created for the new account. */
    fun restart(c: Context) {
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        c.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}
