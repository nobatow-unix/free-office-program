package com.icqnew.app.data.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.icqnew.app.IcqApp
import com.icqnew.app.domain.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Buttons of a message notification: «Прочитано» and «Ответить» (the answer is typed right in the
 * notification). Works even when the app is closed: it connects, does the job and removes the notification.
 */
class NotificationActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getLongExtra(Notifier.EXTRA_PEER, 0L)
        if (peer == 0L) return
        val c = (context.applicationContext as IcqApp).container
        val reply = if (intent.action == ACTION_REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        } else null
        // the notification goes away at once (otherwise Android keeps a spinner on it)
        runCatching { NotificationManagerCompat.from(context).cancel(peer.toInt()) }
        val pending = goAsync()
        ConnectionService.start(context)
        c.appScope.launch {
            try {
                val repo = c.chatRepository
                if (!reply.isNullOrEmpty()) repo.sendMessage(peer, reply, null) // queued, sent as soon as connected
                // wait for the connection and the fresh messages, then mark the chat as read
                withTimeoutOrNull(9_000) { repo.connection.first { it == ConnectionState.CONNECTED } }
                delay(1_200)
                repo.markRead(peer)
            } catch (e: Exception) {
                // nothing else to do from a notification
            } finally {
                runCatching { NotificationManagerCompat.from(context).cancel(peer.toInt()) }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "com.icqnew.app.action.READ"
        const val ACTION_REPLY = "com.icqnew.app.action.REPLY"
        const val KEY_REPLY = "reply_text"
    }
}
