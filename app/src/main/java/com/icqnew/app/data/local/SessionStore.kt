package com.icqnew.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "icq_session")

/** Token, own profile, server URL and all settings — persisted in DataStore. */
class SessionStore(private val context: Context) {

    data class Snapshot(
        val token: String?,
        val uin: Long,
        val name: String,
        val avatarUrl: String?,
        val serverUrl: String,
        val soundOn: Boolean,
        val searchable: Boolean,
        val showOnline: Boolean,
        val sendRead: Boolean,
        val sendTyping: Boolean,
        val blockScreenshots: Boolean,
        val about: String,
        val phone: String,
        val theme: String,
        val accent: Int,
        val fontScale: Float,
        val vibrate: Boolean,
        val notifyPreview: Boolean,
        val pushToken: String,
        val username: String,
        val verified: Boolean,
        val wallpaper: String,
        val soundChoice: String = "icq",
        val swipeAction: String = "delete",
        val archived: Set<String> = emptySet(),
        /** chat folders as "name\tpeer,peer,…" lines */
        val folders: String = "",
        val language: String = "ru",
        val avatarPrivacy: String = "all",
        val phonePrivacy: String = "contacts",
    )

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val UIN = longPreferencesKey("uin")
        val NAME = stringPreferencesKey("name")
        val AVATAR = stringPreferencesKey("avatar")
        val SERVER = stringPreferencesKey("server")
        val AUTO_SERVER = stringPreferencesKey("auto_server")
        val SOUND = booleanPreferencesKey("sound")
        val SEARCHABLE = booleanPreferencesKey("searchable")
        val SHOW_ONLINE = booleanPreferencesKey("show_online")
        val SEND_READ = booleanPreferencesKey("send_read")
        val SEND_TYPING = booleanPreferencesKey("send_typing")
        val BLOCK_SHOTS = booleanPreferencesKey("block_shots")
        val ABOUT = stringPreferencesKey("about")
        val PHONE = stringPreferencesKey("phone")
        val THEME = stringPreferencesKey("theme")
        val ACCENT = intPreferencesKey("accent")
        val FONT = floatPreferencesKey("font_scale")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val PREVIEW = booleanPreferencesKey("notify_preview")
        val PUSH = stringPreferencesKey("push_token")
        val USERNAME = stringPreferencesKey("username")
        val VERIFIED = booleanPreferencesKey("verified")
        val WALLPAPER = stringPreferencesKey("wallpaper")
        val AVATAR_PRIV = stringPreferencesKey("avatar_privacy")
        val SOUND_CHOICE = stringPreferencesKey("sound_choice")
        val SWIPE = stringPreferencesKey("swipe_action")
        val ARCHIVED = androidx.datastore.preferences.core.stringSetPreferencesKey("archived")
        val FOLDERS = androidx.datastore.preferences.core.stringPreferencesKey("folders")
        val LANGUAGE = androidx.datastore.preferences.core.stringPreferencesKey("language")
        val PHONE_PRIV = stringPreferencesKey("phone_privacy")
    }

    val snapshots: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            token = p[Keys.TOKEN],
            uin = p[Keys.UIN] ?: 0L,
            name = p[Keys.NAME] ?: "",
            avatarUrl = p[Keys.AVATAR],
            serverUrl = p[Keys.SERVER] ?: p[Keys.AUTO_SERVER] ?: DEFAULT_SERVER,
            soundOn = p[Keys.SOUND] ?: true,
            searchable = p[Keys.SEARCHABLE] ?: true,
            showOnline = p[Keys.SHOW_ONLINE] ?: true,
            sendRead = p[Keys.SEND_READ] ?: true,
            sendTyping = p[Keys.SEND_TYPING] ?: true,
            blockScreenshots = p[Keys.BLOCK_SHOTS] ?: false,
            about = p[Keys.ABOUT] ?: "",
            phone = p[Keys.PHONE] ?: "",
            theme = p[Keys.THEME] ?: "SYSTEM",
            accent = p[Keys.ACCENT] ?: 0,
            fontScale = p[Keys.FONT] ?: 1f,
            vibrate = p[Keys.VIBRATE] ?: true,
            notifyPreview = p[Keys.PREVIEW] ?: true,
            pushToken = p[Keys.PUSH] ?: "",
            username = p[Keys.USERNAME] ?: "",
            verified = p[Keys.VERIFIED] ?: false,
            wallpaper = p[Keys.WALLPAPER] ?: "",
            avatarPrivacy = p[Keys.AVATAR_PRIV] ?: "all",
            soundChoice = p[Keys.SOUND_CHOICE] ?: "icq",
            swipeAction = p[Keys.SWIPE] ?: "delete",
            archived = p[Keys.ARCHIVED] ?: emptySet(),
            folders = p[Keys.FOLDERS] ?: "",
            language = p[Keys.LANGUAGE] ?: "ru",
            phonePrivacy = p[Keys.PHONE_PRIV] ?: "contacts",
        )
    }

    suspend fun snapshot(): Snapshot = snapshots.first()

    suspend fun saveSession(
        token: String, uin: Long, name: String, avatarUrl: String?, searchable: Boolean, showOnline: Boolean,
        about: String, phone: String,
    ) {
        context.dataStore.edit {
            it[Keys.ABOUT] = about
            it[Keys.PHONE] = phone
            it[Keys.TOKEN] = token
            it[Keys.UIN] = uin
            it[Keys.NAME] = name
            if (avatarUrl != null) it[Keys.AVATAR] = avatarUrl else it.remove(Keys.AVATAR)
            it[Keys.SEARCHABLE] = searchable
            it[Keys.SHOW_ONLINE] = showOnline
        }
    }

    suspend fun updateProfile(name: String, avatarUrl: String?, about: String, phone: String? = null) {
        context.dataStore.edit {
            it[Keys.NAME] = name
            it[Keys.ABOUT] = about
            if (!phone.isNullOrEmpty()) it[Keys.PHONE] = phone
            if (avatarUrl != null) it[Keys.AVATAR] = avatarUrl else it.remove(Keys.AVATAR)
        }
    }

    suspend fun setServerPrivacy(searchable: Boolean, showOnline: Boolean, avatarPrivacy: String? = null, phonePrivacy: String? = null) {
        context.dataStore.edit {
            it[Keys.SEARCHABLE] = searchable
            it[Keys.SHOW_ONLINE] = showOnline
            if (avatarPrivacy != null) it[Keys.AVATAR_PRIV] = avatarPrivacy
            if (phonePrivacy != null) it[Keys.PHONE_PRIV] = phonePrivacy
        }
    }

    suspend fun setLocalPrivacy(sendRead: Boolean, sendTyping: Boolean, blockScreenshots: Boolean) {
        context.dataStore.edit {
            it[Keys.SEND_READ] = sendRead
            it[Keys.SEND_TYPING] = sendTyping
            it[Keys.BLOCK_SHOTS] = blockScreenshots
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.TOKEN)
            it.remove(Keys.UIN)
            it.remove(Keys.NAME)
            it.remove(Keys.AVATAR)
            it.remove(Keys.ABOUT)
            it.remove(Keys.PHONE)
        }
    }

    suspend fun setTheme(mode: String) {
        context.dataStore.edit { it[Keys.THEME] = mode }
    }

    suspend fun setAccent(i: Int) {
        context.dataStore.edit { it[Keys.ACCENT] = i }
    }

    suspend fun setFontScale(f: Float) {
        context.dataStore.edit { it[Keys.FONT] = f }
    }

    suspend fun setVibrate(on: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATE] = on }
    }

    suspend fun setNotifyPreview(on: Boolean) {
        context.dataStore.edit { it[Keys.PREVIEW] = on }
    }

    suspend fun setIdentity(username: String?, verified: Boolean) {
        context.dataStore.edit {
            if (username.isNullOrEmpty()) it.remove(Keys.USERNAME) else it[Keys.USERNAME] = username
            it[Keys.VERIFIED] = verified
        }
    }

    suspend fun setWallpaper(spec: String) {
        context.dataStore.edit { it[Keys.WALLPAPER] = spec }
    }

    suspend fun setPushToken(t: String) {
        context.dataStore.edit { it[Keys.PUSH] = t }
    }

    /** True if the user typed a server address himself (then we never change it). */
    suspend fun hasCustomServer(): Boolean = context.dataStore.data.first()[Keys.SERVER] != null

    suspend fun setAutoServer(url: String) {
        context.dataStore.edit { it[Keys.AUTO_SERVER] = url }
    }

    suspend fun setSoundChoice(v: String) {
        context.dataStore.edit { it[Keys.SOUND_CHOICE] = v }
    }

    suspend fun setSwipeAction(v: String) {
        context.dataStore.edit { it[Keys.SWIPE] = v }
    }

    suspend fun setFolders(raw: String) {
        context.dataStore.edit { it[Keys.FOLDERS] = raw }
    }

    suspend fun setLanguage(code: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = code }
    }

    suspend fun setArchived(peer: Long, archived: Boolean) {
        context.dataStore.edit {
            val cur = it[Keys.ARCHIVED] ?: emptySet()
            it[Keys.ARCHIVED] = if (archived) cur + peer.toString() else cur - peer.toString()
        }
    }

    suspend fun setSound(on: Boolean) {
        context.dataStore.edit { it[Keys.SOUND] = on }
    }

    companion object {
        /** The server this build talks to (not shown anywhere in the UI). */
        const val DEFAULT_SERVER = "https://icq-new.online"

        /** Tried in this order when the user did not type a server himself. */
        val SERVER_CANDIDATES = listOf(
            DEFAULT_SERVER, "http://icq-new.online", "http://185.159.128.234", "http://185.159.128.234:8000",
        )
    }
}
