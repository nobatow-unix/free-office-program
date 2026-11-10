package com.icqnew.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.icqnew.app.data.remote.AppVersionDto
import com.icqnew.app.data.remote.IcqApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "A new version is available": asks our server which version is the newest
 * (apk/version.json), downloads the APK and opens the system installer.
 */
class Updater(private val context: Context, private val api: IcqApi, private val scope: CoroutineScope) {

    data class State(
        val available: AppVersionDto? = null,
        /** null = not downloading, 0..1 = progress */
        val progress: Float? = null,
        val error: String? = null,
        val dismissed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val currentCode: Long
        get() = runCatching {
            val p = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        }.getOrDefault(0L)

    val currentName: String
        get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

    fun check() {
        scope.launch {
            val v = runCatching { api.appVersion() }.getOrNull() ?: return@launch
            if (v.code > currentCode) _state.value = _state.value.copy(available = v, error = null)
        }
    }

    fun dismiss() {
        _state.value = _state.value.copy(dismissed = true)
    }

    fun downloadAndInstall() {
        val v = _state.value.available ?: return
        if (_state.value.progress != null) return
        scope.launch {
            // the big banner goes away: only the small circle next to «ICQ» shows the download
            _state.value = _state.value.copy(progress = 0f, error = null, dismissed = true)
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "ICQ-New-${v.name.ifBlank { v.code.toString() }}.apk")
                // 1) only the difference from the installed version (usually a few MB),
                // 2) if there is no patch or the result does not match — the whole APK
                val viaPatch = v.sha256.isNotBlank() && runCatching {
                    val patch = File(dir, "update.icqdiff")
                    api.downloadUpdate("/api/app/patch/$currentCode", patch) { p ->
                        _state.value = _state.value.copy(progress = p * 0.9f)
                    }
                    withContext(Dispatchers.Default) {
                        DeltaPatch.apply(File(context.applicationInfo.sourceDir), patch, f)
                    }
                    patch.delete()
                    DeltaPatch.sha256(f).equals(v.sha256, ignoreCase = true)
                }.getOrDefault(false)
                if (!viaPatch) {
                    f.delete()
                    api.downloadUpdate(v.url, f) { p -> _state.value = _state.value.copy(progress = p) }
                }
                _state.value = _state.value.copy(progress = null)
                withContext(Dispatchers.IO) { keepCopy(f) }
                withContext(Dispatchers.Main) { install(f) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(progress = null, dismissed = false, error = "Не удалось скачать обновление")
            }
        }
    }

    /** A copy of the update stays in «Загрузки/ICQ» (the older copy made by the app is replaced). */
    private fun keepCopy(f: File) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            val res = context.contentResolver
            val col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // our own earlier copies (the app can see only the files it made itself)
            res.query(
                col, arrayOf(android.provider.MediaStore.MediaColumns._ID),
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " +
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                arrayOf("Download/ICQ%", "ICQ-New-%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    runCatching { res.delete(android.content.ContentUris.withAppendedId(col, c.getLong(0)), null, null) }
                }
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ICQ")
            }
            val uri = res.insert(col, values) ?: return
            res.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        }
    }

    private fun install(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            // Android asks once: "allow installing apps from ICQ New"
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = _state.value.copy(dismissed = false, error = "Разрешите установку и нажмите «Обновить» ещё раз")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
