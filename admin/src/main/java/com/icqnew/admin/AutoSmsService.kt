package com.icqnew.admin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Automatic SMS: while it runs, every code that somebody asks for is sent from THIS phone's SIM card.
 * The server hands each code to exactly one phone (several admin phones can be on at the same time).
 */
class AutoSmsService : Service() {

    data class State(
        val running: Boolean = false,
        val status: String = "",
        val sent: Int = 0,
        val last: String = "",
        val error: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        channel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Core.prefs.autoSms = false
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification("Авто-SMS включено", "Ждём новые коды…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTE_ID, n)
        if (loop?.isActive != true) start()
        return START_STICKY
    }

    private fun start() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "icqadmin:autosms").apply { setReferenceCounted(false); acquire() }
        state.value = state.value.copy(running = true, status = "Ждём новые коды…", error = "")
        val device = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL
        loop = scope.launch {
            Core.api.smsAuto(true)
            var backoff = 2_000L
            while (isActive) {
                try {
                    val job = Core.api.nextSms(device)
                    backoff = 2_000L
                    if (job == null) {
                        setStatus("Ждём новые коды…")
                        continue
                    }
                    setStatus("Отправляем код на ${job.phone}…")
                    val err = Sms.send(this@AutoSmsService, job.phone, job.text, Core.prefs.simId)
                    runCatching { Core.api.smsResult(job.phone, job.code, err == null, err) }
                    if (err == null) {
                        Core.prefs.sentToday = Core.prefs.sentToday + 1
                        state.value = state.value.copy(sent = state.value.sent + 1, last = job.phone, error = "")
                        setStatus("Отправлено: ${job.phone}")
                    } else {
                        state.value = state.value.copy(error = "${job.phone}: $err")
                        setStatus("Не ушло: $err")
                        delay(3_000)
                    }
                } catch (e: ApiError) {
                    if (e.code == 401 || e.code == 403) {
                        state.value = state.value.copy(error = "Нет доступа к SMS: войдите снова")
                        stopSelf()
                        return@launch
                    }
                    setStatus("Сервер: ${e.message}")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                } catch (e: Exception) {
                    setStatus("Нет связи с сервером, повтор…")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    private fun setStatus(s: String) {
        state.value = state.value.copy(status = s)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTE_ID, notification("Авто-SMS · сегодня ${Core.prefs.sentToday}", s))
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutoSmsService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification), "Выключить", stop,
                ).build(),
            )
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        val wasOn = Core.prefs.autoSms
        scope.launch {
            if (!wasOn) Core.api.smsAuto(false)
        }.invokeOnCompletion { scope.cancel() }
        runCatching { wake?.release() }
        state.value = state.value.copy(running = false, status = "Выключено")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "auto_sms"
        const val NOTE_ID = 7
        const val ACTION_STOP = "stop"
        val state = MutableStateFlow(State())

        fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Авто-SMS", NotificationManager.IMPORTANCE_LOW))
        }

        fun start(ctx: Context) {
            Core.init(ctx)
            Core.prefs.autoSms = true
            ctx.startForegroundService(Intent(ctx, AutoSmsService::class.java))
        }

        fun stop(ctx: Context) {
            Core.init(ctx)
            Core.prefs.autoSms = false
            ctx.stopService(Intent(ctx, AutoSmsService::class.java))
        }
    }
}

/** After a reboot / an update the automatic SMS goes on by itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        if (Core.prefs.autoSms && Core.prefs.token != null && Sms.canSend(context)) {
            runCatching { context.startForegroundService(Intent(context, AutoSmsService::class.java)) }
        }
    }
}
