package com.icqnew.app.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records a voice message (AAC in an .m4a file) into the private outbox. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()

    fun start(): Boolean {
        val dir = File(context.filesDir, "outbox").apply { mkdirs() }
        val f = File(dir, "voice_" + System.currentTimeMillis() + ".m4a")
        val r = newRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            r.release()
            f.delete()
            false
        }
    }

    /** Stops and returns the file + its duration, or null if it was too short / failed. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        recorder = null
        val duration = System.currentTimeMillis() - startedAt
        val f = file
        file = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            r.release()
            f?.delete()
            return null
        }
        r.release()
        if (f == null) return null
        if (duration < 700) {
            f.delete()
            return null
        }
        return f to duration
    }

    /** Loudness 0..1 since the last call (for the live bars while recording). */
    fun level(): Float = try {
        ((recorder?.maxAmplitude ?: 0) / 20_000f).coerceIn(0f, 1f)
    } catch (e: Exception) {
        0f
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // too short to stop cleanly
        }
        r.release()
        file?.delete()
        file = null
    }
}
