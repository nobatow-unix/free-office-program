package com.icqnew.app.data.notify

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.icqnew.app.IcqApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging: the server sends a data push when the user has no open
 * connection (app closed). We show the notification ourselves and wake the connection.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val c = (application as IcqApp).container
        c.appScope.launch { c.chatRepository.registerPushToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val c = (application as IcqApp).container
        val d = message.data
        val peer = d["peer"]?.toLongOrNull() ?: return
        val title = d["title"] ?: "ICQ"
        // Connect so that the message history (or the call offer) arrives right away.
        ConnectionService.start(this)
        when (d["type"]) {
            "msg" -> c.appScope.launch {
                if (AppForeground.isForeground()) return@launch
                if (c.chatRepository.observePrefs(peer).first().muted) return@launch
                val preview = c.store.snapshot().notifyPreview
                c.notifier.showMessage(title, if (preview) d["body"] ?: "" else "Новое сообщение", peer)
                c.sound.playIncoming()
            }
            "call" -> if (c.callManager.state.value == null) {
                c.notifier.showIncomingCall(title, peer, d["video"] == "1")
            }
        }
    }
}
