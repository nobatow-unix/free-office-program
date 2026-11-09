package com.icqnew.app.data.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.icqnew.app.MainActivity
import com.icqnew.app.R

object AppForeground {
    fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

/** Message notifications + the small "connected" notification of the background service. */
class Notifier(private val context: Context) {

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val messages = NotificationChannel(CHANNEL_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH)
            messages.setSound(null, null) // the app plays its own sound
            messages.enableVibration(true)
            nm.createNotificationChannel(messages)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "Соединение", NotificationManager.IMPORTANCE_MIN)
            )
            val calls = NotificationChannel(CHANNEL_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH)
            calls.setSound(null, null) // the app plays the ringtone itself
            nm.createNotificationChannel(calls)
        }
    }

    private fun openAppIntent(peer: Long? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (peer != null) putExtra(EXTRA_PEER, peer)
        }
        return PendingIntent.getActivity(
            context, peer?.toInt() ?: 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun cancel(peer: Long) {
        try {
            NotificationManagerCompat.from(context).cancel(peer.toInt())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun showMessage(name: String, text: String, peerUin: Long, canReply: Boolean = true) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setColor(0xFF00CB66.toInt())
            .setContentIntent(openAppIntent(peerUin))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .apply {
                if (canAct(peerUin)) {
                    if (canReply) addAction(replyAction(peerUin))
                    addAction(readAction(peerUin))
                }
            }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(peerUin.toInt(), n)
        } catch (e: SecurityException) {
            // permission revoked meanwhile
        }
    }

    /** No answer buttons for the bot and for channels where only admins write (they would just fail). */
    private fun canAct(peer: Long): Boolean = peer != com.icqnew.app.domain.BOT_UIN

    private fun actionIntent(action: String, peer: Long): Intent =
        Intent(context, NotificationActions::class.java).setAction(action).putExtra(EXTRA_PEER, peer)

    private fun replyAction(peer: Long): NotificationCompat.Action {
        val input = androidx.core.app.RemoteInput.Builder(NotificationActions.KEY_REPLY).setLabel("Сообщение…").build()
        // RemoteInput needs a MUTABLE intent (the system puts the typed text into it)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(context, (peer * 2 + 1).toInt(), actionIntent(NotificationActions.ACTION_REPLY, peer), flags)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, "Ответить", pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun readAction(peer: Long): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, (peer * 2).toInt(), actionIntent(NotificationActions.ACTION_READ, peer),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, "Прочитано", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    /** Full-screen "incoming call" notification (opens the app on top of the lock screen). */
    fun showIncomingCall(name: String, peer: Long, video: Boolean) {
        if (!canNotify()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL, true)
        }
        val pi = PendingIntent.getActivity(
            context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(if (video) "Входящий видеозвонок" else "Входящий звонок")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setColor(0xFF00CB66.toInt())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(CALL_ID, n)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun cancelCall() {
        try {
            NotificationManagerCompat.from(context).cancel(CALL_ID)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun showMissedCall(name: String, peer: Long) {
        if (!canNotify() || AppForeground.isForeground()) return
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Пропущенный звонок")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(peer))
            .setColor(0xFF00CB66.toInt())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(peer.toInt() xor 0x5a5a, n)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ICQ")
            .setContentText("На связи")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
        const val SERVICE_ID = Int.MAX_VALUE
        const val EXTRA_PEER = "open_peer"
        const val EXTRA_CALL = "open_call"
        const val CHANNEL_CALLS = "calls"
        const val CALL_ID = Int.MAX_VALUE - 1
    }
}
