package com.icqnew.app.data.repository

import com.icqnew.app.data.local.IcqDatabase
import com.icqnew.app.data.local.SessionStore
import com.icqnew.app.data.remote.ChatSocket
import com.icqnew.app.data.remote.IcqApi
import com.icqnew.app.domain.AuthRepository
import com.icqnew.app.domain.PrivacySettings
import com.icqnew.app.domain.Session
import com.icqnew.app.domain.SettingsRepository
import com.icqnew.app.domain.ThemeMode
import com.icqnew.app.domain.AppSettings
import com.icqnew.app.domain.DeviceSession
import com.icqnew.app.domain.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AuthRepositoryImpl(
    private val api: IcqApi,
    private val store: SessionStore,
    private val socket: ChatSocket,
    private val db: IcqDatabase,
) : AuthRepository {

    override val session: Flow<Session?> = store.snapshots.map { s ->
        val token = s.token
        if (token != null) Session(token, s.uin, s.name, s.avatarUrl, s.about, s.phone, s.username, s.verified) else null
    }

    override suspend fun requestCode(phone: String): Boolean {
        return api.requestCode(phone).inApp
    }

    override suspend fun verify(phone: String, code: String, name: String?): VerifyResult {
        val r = api.verify(phone, code, name)
        val token = r.token
        val user = r.user
        if (r.needName || token == null || user == null) return VerifyResult.NeedName
        withContext(Dispatchers.IO) { db.clearAllTables() } // never mix data of two accounts
        store.saveSession(
            token, user.uin, user.name, user.avatarUrl, user.searchable, user.showOnline, user.about, user.phone,
        )
        store.setIdentity(user.username, user.verified)
        socket.start()
        return VerifyResult.Done(Session(token, user.uin, user.name, user.avatarUrl, user.about, user.phone))
    }

    override suspend fun updateProfile(name: String, about: String) {
        val u = api.updateProfile(name, about)
        store.updateProfile(u.name, u.avatarUrl, u.about, u.phone)
    }

    override suspend fun setUsername(username: String) {
        val u = api.setUsername(username.trim().removePrefix("@"))
        store.setIdentity(u.username, u.verified)
    }

    override suspend fun checkUsername(username: String): String? {
        val r = api.checkUsername(username.trim().removePrefix("@"))
        return if (r.ok) null else r.text
    }

    override suspend fun sessions(): List<DeviceSession> =
        api.sessions().map { DeviceSession(it.id, it.device, it.created, it.lastUsed, it.current, it.online) }

    override suspend fun endSession(id: String) = api.endSession(id)

    override suspend fun uploadAvatar(jpeg: ByteArray) {
        val u = api.uploadAvatar(jpeg)
        store.updateProfile(u.name, u.avatarUrl, u.about, u.phone)
    }

    override suspend fun logout() {
        runCatching {
            val t = store.snapshot().pushToken
            if (t.isNotEmpty()) api.dropPushToken(t)
        }
        runCatching { api.logout() } // revoke the token on the server (best effort)
        wipeLocal()
    }

    override suspend fun deleteAccount() {
        api.deleteAccount()
        wipeLocal()
    }

    private suspend fun wipeLocal() {
        socket.stop()
        store.clearSession()
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }
}

class SettingsRepositoryImpl(
    private val api: IcqApi,
    private val store: SessionStore,
) : SettingsRepository {

    override val soundOn: Flow<Boolean> = store.snapshots.map { it.soundOn }

    override val privacy: Flow<PrivacySettings> = store.snapshots.map {
        PrivacySettings(
            it.searchable, it.showOnline, it.sendRead, it.sendTyping, it.blockScreenshots,
            avatarPrivacy = it.avatarPrivacy, phonePrivacy = it.phonePrivacy,
        )
    }

    override val theme: Flow<ThemeMode> = store.snapshots.map {
        runCatching { ThemeMode.valueOf(it.theme) }.getOrDefault(ThemeMode.SYSTEM)
    }

    override suspend fun setSound(on: Boolean) = store.setSound(on)
    override suspend fun setSoundChoice(v: String) = store.setSoundChoice(v)
    override suspend fun setSwipeAction(v: String) = store.setSwipeAction(v)
    override suspend fun setArchived(peer: Long, archived: Boolean) = store.setArchived(peer, archived)

    override suspend fun setTheme(mode: ThemeMode) = store.setTheme(mode.name)

    override val app: Flow<AppSettings> = store.snapshots.map {
        AppSettings(
            wallpaper = it.wallpaper,
            theme = runCatching { ThemeMode.valueOf(it.theme) }.getOrDefault(ThemeMode.SYSTEM),
            accent = it.accent,
            fontScale = it.fontScale,
            vibrate = it.vibrate,
            notifyPreview = it.notifyPreview,
            sound = it.soundOn,
            soundChoice = it.soundChoice,
            swipeAction = it.swipeAction,
            archived = it.archived.mapNotNull { k -> k.toLongOrNull() }.toSet(),
        )
    }

    override suspend fun setAccent(index: Int) = store.setAccent(index)
    override suspend fun setFontScale(scale: Float) = store.setFontScale(scale)
    override suspend fun setVibrate(on: Boolean) = store.setVibrate(on)
    override suspend fun setNotifyPreview(on: Boolean) = store.setNotifyPreview(on)
    override suspend fun setWallpaper(spec: String) = store.setWallpaper(spec)

    override suspend fun setPrivacy(p: PrivacySettings) {
        store.setLocalPrivacy(p.sendReadReceipts, p.sendTyping, p.blockScreenshots)
        val cur = store.snapshot()
        if (cur.searchable != p.searchable || cur.showOnline != p.showOnline ||
            cur.avatarPrivacy != p.avatarPrivacy || cur.phonePrivacy != p.phonePrivacy
        ) {
            val u = api.setPrivacy(p.searchable, p.showOnline, p.avatarPrivacy, p.phonePrivacy)
            store.setServerPrivacy(u.searchable, u.showOnline, u.avatarPrivacy, u.phonePrivacy)
        }
    }
}
