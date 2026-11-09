package com.icqnew.app.data.remote

import com.icqnew.app.data.local.SessionStore
import com.icqnew.app.domain.ConnectionState
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Keeps one WebSocket open to the server, reconnecting with back-off.
 * Incoming frames are exposed as [events]; outgoing via [send].
 */
class ChatSocket(
    private val client: HttpClient,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val json: Json,
) {
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WsIn>(extraBufferCapacity = 128)
    val events: SharedFlow<WsIn> = _events.asSharedFlow()

    @Volatile private var active: DefaultClientWebSocketSession? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val s = store.snapshot()
                val token = s.token
                if (token == null) {
                    _state.value = ConnectionState.DISCONNECTED
                    delay(1_000)
                    continue
                }
                _state.value = ConnectionState.CONNECTING
                try {
                    client.webSocket(urlString = wsBase(s.serverUrl) + "/ws?token=" + token) {
                        active = this
                        _state.value = ConnectionState.CONNECTED
                        backoff = 1_000L
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val ev = runCatching { json.decodeFromString<WsIn>(frame.readText()) }.getOrNull()
                                if (ev != null) _events.emit(ev)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // connection failed or dropped — retry below
                }
                active = null
                _state.value = ConnectionState.DISCONNECTED
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        active = null
        _state.value = ConnectionState.DISCONNECTED
    }

    /** Returns false when there is no open connection (message stays queued in Room). */
    suspend fun send(out: WsOut): Boolean {
        val s = active ?: return false
        return try {
            s.send(Frame.Text(json.encodeToString(out)))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun wsBase(http: String): String =
        if (http.startsWith("https")) "wss" + http.removePrefix("https") else "ws" + http.removePrefix("http")
}
