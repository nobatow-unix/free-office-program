package com.icqnew.app.data.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.icqnew.app.IcqApp
import kotlinx.coroutines.launch

/** Foreground service that keeps the WebSocket alive when the app is in the background. */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as IcqApp).container
        try {
            startForeground(Notifier.SERVICE_ID, container.notifier.serviceNotification())
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        container.appScope.launch {
            if (container.store.snapshot().token == null) stopSelf() else container.socket.start()
        }
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (e: Exception) {
                // not allowed right now (app in background) — will start next time the app opens
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
