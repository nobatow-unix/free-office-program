package com.icqnew.app.data.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import com.icqnew.app.data.local.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Notification sounds. By default they are synthesised at runtime (two descending notes for
 * an incoming message). To use your own recording, put files named notify_in.mp3 / notify_out.mp3
 * into app/src/main/res/raw/ — they are picked up automatically.
 */
class SoundPlayer(
    private val context: Context,
    private val store: SessionStore,
    private val scope: CoroutineScope,
) {
    private class Note(val freq: Double, val ms: Int)

    fun playIncoming() {
        scope.launch(Dispatchers.Main) {
            val snap = runCatching { store.snapshot() }.getOrNull()
            when (snap?.soundChoice ?: "icq") {
                "none" -> Unit
                "classic" -> play(listOf(Note(784.0, 130), Note(0.0, 35), Note(587.0, 230)))
                "system" -> playSystem()
                else -> if (!playRaw("notify_in")) play(listOf(Note(784.0, 130), Note(0.0, 35), Note(587.0, 230)))
            }
        }
        vibrate()
    }

    /** Plays one of the choices once (Settings → sound preview). */
    fun preview(choice: String) {
        scope.launch(Dispatchers.Main) {
            when (choice) {
                "classic" -> play(listOf(Note(784.0, 130), Note(0.0, 35), Note(587.0, 230)), force = true)
                "system" -> playSystem()
                "none" -> Unit
                else -> playRaw("notify_in", force = true)
            }
        }
    }

    private fun playSystem() {
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = NOTIFY_ATTRS
                play()
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    /** Short buzz on a new message (Settings -> Vibration). */
    fun vibrate() {
        scope.launch {
            try {
                if (!store.snapshot().vibrate) return@launch
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return@launch
                v.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) {
                // best-effort
            }
        }
    }

    fun playSend() {
        if (!playRaw("notify_out")) play(listOf(Note(988.0, 55), Note(1319.0, 80)))
    }

    /** Returns true if a custom raw resource with this name exists (playback is started). */
    private fun playRaw(name: String, force: Boolean = false): Boolean {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        if (id == 0) return false
        scope.launch(Dispatchers.Main) {
            try {
                if (!force && !store.snapshot().soundOn) return@launch
                // notification stream (not "media"): follows the phone's notification volume and silent mode
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return@launch
                val mp = MediaPlayer.create(context, id, NOTIFY_ATTRS, am.generateAudioSessionId()) ?: return@launch
                mp.setOnCompletionListener { it.release() }
                mp.start()
            } catch (e: Exception) {
                // sound is best-effort
            }
        }
        return true
    }

    private fun play(notes: List<Note>, force: Boolean = false) {
        scope.launch(Dispatchers.Default) {
            try {
                if (!force && !store.snapshot().soundOn) return@launch
                val pcm = synth(notes)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                delay(pcm.size * 1000L / SAMPLE_RATE + 120)
                track.release()
            } catch (e: Exception) {
                // sound is best-effort
            }
        }
    }

    private fun synth(notes: List<Note>): ShortArray {
        var total = 0
        for (n in notes) total += n.ms * SAMPLE_RATE / 1000
        val out = ShortArray(total)
        var pos = 0
        for (n in notes) {
            val len = n.ms * SAMPLE_RATE / 1000
            val dur = len.toDouble() / SAMPLE_RATE
            for (i in 0 until len) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = min(1.0, t / 0.006)
                val decay = exp(-3.5 * t / dur)
                val release = min(1.0, (dur - t) / 0.012)
                val s = if (n.freq == 0.0) 0.0
                else (sin(2 * PI * n.freq * t) + 0.35 * sin(2 * PI * n.freq * 2 * t)) / 1.35
                out[pos + i] = (s * attack * decay * release * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
            pos += len
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 22050
        val NOTIFY_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
