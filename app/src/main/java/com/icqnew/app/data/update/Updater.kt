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
            _state.value = _state.value.copy(progress = 0f, error = null)
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "ICQ-New-${v.name.ifBlank { v.code.toString() }}.apk")
                api.downloadUpdate(v.url, f) { p -> _state.value = _state.value.copy(progress = p) }
                _state.value = _state.value.copy(progress = null)
                withContext(Dispatchers.Main) { install(f) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(progress = null, error = "Не удалось скачать обновление")
            }
        }
    }

    private fun install(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            // Android asks once: "allow installing apps from ICQ New"
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = _state.value.copy(error = "Разрешите установку и нажмите «Обновить» ещё раз")
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
