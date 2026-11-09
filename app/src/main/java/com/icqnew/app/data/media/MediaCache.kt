package com.icqnew.app.data.media

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import com.icqnew.app.data.local.SessionStore
import com.icqnew.app.data.remote.IcqApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Downloads attachments (authenticated) into the app cache; saves files to the Downloads folder. */
class MediaCache(
    private val context: Context,
    private val api: IcqApi,
    private val store: SessionStore,
) {
    private fun dir(): File = File(context.cacheDir, "media").apply { mkdirs() }

    /** Keeps the downloaded-media cache under [maxBytes] (oldest files are removed first). */
    fun trim(maxBytes: Long = 150L * 1024 * 1024) {
        try {
            val files = dir().listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= maxBytes) break
                total -= f.length()
                f.delete()
            }
            // sent originals in the outbox are not needed after a week
            val week = System.currentTimeMillis() - 7L * 86_400_000
            File(context.filesDir, "outbox").listFiles()?.filter { it.lastModified() < week }?.forEach { it.delete() }
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Local file for an attachment, downloading it on first use. */
    suspend fun file(attId: String, name: String): File? = withContext(Dispatchers.IO) {
        val f = File(dir(), attId + "_" + name.replace('/', '_'))
        if (f.exists() && f.length() > 0) return@withContext f
        try {
            api.download(attId, f)
            f
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    /** URL + Authorization header to stream an attachment (video / audio) without downloading it first. */
    suspend fun streamSource(attId: String): Pair<String, Map<String, String>> {
        val s = store.snapshot()
        return (s.serverUrl + "/api/files/" + attId) to (s.token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
    }

    /** Hands the file to the system download manager (shows a notification when finished). */
    suspend fun saveToDownloads(attId: String, name: String, mime: String?): Boolean {
        val s = store.snapshot()
        val token = s.token ?: return false
        return try {
            val req = DownloadManager.Request((s.serverUrl + "/api/files/" + attId).toUri())
                .addRequestHeader("Authorization", "Bearer $token")
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mime.isNullOrBlank()) req.setMimeType(mime)
            context.getSystemService(DownloadManager::class.java).enqueue(req)
            true
        } catch (e: Exception) {
            false
        }
    }
}
