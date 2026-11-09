package com.icqnew.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File

/** A file picked by the user and copied into the app's private outbox. */
data class PickedFile(val file: File, val name: String, val mime: String)

object FileUtils {
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024 // 4 GB

    /** Copies [uri] into the private outbox (so it can be re-uploaded later). Returns null if too big / unreadable. */
    fun copyToOutbox(context: Context, uri: Uri): PickedFile? {
        return try {
            var name = "file"
            var size = -1L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n) ?: name
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
            if (size > MAX_BYTES) return null
            val dir = File(context.filesDir, "outbox").apply { mkdirs() }
            val out = File(dir, System.currentTimeMillis().toString() + "_" + name.replace('/', '_'))
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
            if (out.length() > MAX_BYTES) {
                out.delete()
                return null
            }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            PickedFile(out, name, mime)
        } catch (e: Exception) {
            null
        }
    }

    /** Empty file in the outbox for a camera photo (shared with the camera app via FileProvider). */
    fun newCameraFile(context: Context): File {
        val dir = File(context.filesDir, "outbox").apply { mkdirs() }
        return File(dir, "photo_" + System.currentTimeMillis() + ".jpg")
    }

    /** Centre-crops the picked image to a 512x512 JPEG for the profile photo. */
    fun avatarJpeg(context: Context, uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val side = minOf(bmp.width, bmp.height)
            val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, 512, 512, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> String.format("%.1f МБ", bytes / 1048576.0)
    }
}
