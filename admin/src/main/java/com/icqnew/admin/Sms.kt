package com.icqnew.admin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class Sim(val id: Int, val label: String)

object Sms {
    private const val ACTION_SENT = "icq.admin.SMS_SENT"

    fun canSend(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** SIM cards of this phone (for phones with two SIMs). */
    @SuppressLint("MissingPermission")
    fun sims(ctx: Context): List<Sim> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            sm.activeSubscriptionInfoList.orEmpty().map { s ->
                Sim(s.subscriptionId, "SIM ${s.simSlotIndex + 1}: " + (s.carrierName?.toString() ?: s.displayName?.toString() ?: ""))
            }
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context, simId: Int): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        return if (simId >= 0) {
            if (Build.VERSION.SDK_INT >= 31) base.createForSubscriptionId(simId) else SmsManager.getSmsManagerForSubscriptionId(simId)
        } else base
    }

    /**
     * Sends one SMS and waits for the phone network's answer.
     * Returns null when it went out, otherwise the reason.
     */
    suspend fun send(ctx: Context, phone: String, text: String, simId: Int): String? {
        if (!canSend(ctx)) return "Нет разрешения на SMS"
        val app = ctx.applicationContext
        val id = System.nanoTime().toString()
        val result = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<String?> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (i.getStringExtra("id") != id) return
                        runCatching { app.unregisterReceiver(this) }
                        val err = when (resultCode) {
                            Activity.RESULT_OK -> null
                            SmsManager.RESULT_ERROR_NO_SERVICE -> "Нет сети"
                            SmsManager.RESULT_ERROR_RADIO_OFF -> "Режим полёта / радио выключено"
                            SmsManager.RESULT_ERROR_NULL_PDU -> "Ошибка SMS"
                            else -> "Ошибка отправки ($resultCode)"
                        }
                        if (cont.isActive) cont.resume(err ?: "")
                    }
                }
                ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
                try {
                    val sm = manager(app, simId)
                    val sent = PendingIntent.getBroadcast(
                        app, id.hashCode(),
                        Intent(ACTION_SENT).setPackage(app.packageName).putExtra("id", id),
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val parts = sm.divideMessage(text)
                    if (parts.size > 1) {
                        // only the last part reports back
                        val intents = ArrayList<PendingIntent?>(List(parts.size) { i -> if (i == parts.lastIndex) sent else null })
                        @Suppress("UNCHECKED_CAST")
                        sm.sendMultipartTextMessage(phone, null, parts, intents as ArrayList<PendingIntent>, null)
                    } else {
                        sm.sendTextMessage(phone, null, text, sent, null)
                    }
                } catch (e: Exception) {
                    runCatching { app.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(e.message ?: "Ошибка отправки")
                }
            }
        }
        return when (result) {
            null -> "Нет ответа от сети (60 с)"
            "" -> null
            else -> result
        }
    }
}
