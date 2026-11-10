package com.icqnew.app.di

import android.content.Context
import androidx.room.Room
import com.icqnew.app.data.local.IcqDatabase
import com.icqnew.app.data.call.CallManager
import com.icqnew.app.data.local.PhoneContacts
import com.icqnew.app.data.local.SessionStore
import com.icqnew.app.data.media.MediaCache
import com.icqnew.app.data.media.VoicePlayer
import com.icqnew.app.data.media.VoiceRecorder
import com.icqnew.app.data.notify.Notifier
import com.icqnew.app.data.remote.ChatSocket
import com.icqnew.app.data.remote.IcqApi
import com.icqnew.app.data.repository.AuthRepositoryImpl
import com.icqnew.app.data.repository.ChatRepositoryImpl
import com.icqnew.app.data.repository.SettingsRepositoryImpl
import com.icqnew.app.data.sound.SoundPlayer
import com.icqnew.app.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.ktor.client.request.get
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

/** Hand-written DI container: one instance per process, owned by [com.icqnew.app.IcqApp]. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val appContext: Context get() = context

    @OptIn(ExperimentalSerializationApi::class)
    private val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** Chat to open (set when the user taps a message notification). */
    val openChatRequest = MutableStateFlow<Long?>(null)

    /** A link to a person / group / channel that should be opened (see IcqLinks). */
    val openLinkRequest = MutableStateFlow<String?>(null)

    val store = SessionStore(context)

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(appJson) }
        install(WebSockets)
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(120, TimeUnit.SECONDS)
                pingInterval(20, TimeUnit.SECONDS)
            }
        }
    }

    private val db = Room.databaseBuilder(context, IcqDatabase::class.java, com.icqnew.app.data.local.Accounts.dbName(context))
        .addMigrations(com.icqnew.app.data.local.MIGRATION_6_7, com.icqnew.app.data.local.MIGRATION_7_8, com.icqnew.app.data.local.MIGRATION_8_9, com.icqnew.app.data.local.MIGRATION_9_10, com.icqnew.app.data.local.MIGRATION_10_11)
        .fallbackToDestructiveMigration()
        .build()
    private val api = IcqApi(httpClient, store)
    val socket = ChatSocket(httpClient, store, appScope, appJson)
    val notifier = Notifier(context)
    val sound = SoundPlayer(context, store, appScope)

    val mediaCache = MediaCache(context, api, store)
    val voicePlayer = VoicePlayer(mediaCache, appScope)
    fun newVoiceRecorder() = VoiceRecorder(context)

    private val phoneBook: PhoneBook = PhoneContacts(context)

    val authRepository: AuthRepository = AuthRepositoryImpl(api, store, socket, db)
    val settingsRepository: SettingsRepository = SettingsRepositoryImpl(api, store)
    val chatRepository: ChatRepository = ChatRepositoryImpl(api, socket, db, store, sound, notifier, appScope)

    init {
        // a voice message that starts playing counts as listened (the dot goes away, also for the sender)
        voicePlayer.onStart = { id -> appScope.launch { runCatching { chatRepository.markPlayed(id) } } }
    }
    val callManager = CallManager(context, socket, api, db, notifier, appScope)
    val updater = com.icqnew.app.data.update.Updater(context, api, appScope)

    val requestCodeUseCase = RequestCodeUseCase(authRepository)
    val verifyCodeUseCase = VerifyCodeUseCase(authRepository)
    val observeChats = ObserveChatsUseCase(chatRepository)
    val observeMessages = ObserveMessagesUseCase(chatRepository)
    val sendMessage = SendMessageUseCase(chatRepository)
    val searchUsers = SearchUsersUseCase(chatRepository)
    val syncPhoneBook = SyncPhoneBookUseCase(chatRepository, phoneBook)

    /** Reconnect automatically if the user is already logged in. */
    fun startIfLoggedIn() {
        appScope.launch {
            pickServer()
            if (store.snapshots.first().token != null) socket.start()
        }
        registerPush()
    }

    /**
     * icq-new.online over HTTPS first; if it cannot be reached (blocked / no certificate yet),
     * plain HTTP, then the bare IP. The user's own address (if he typed one) is never touched.
     */
    private suspend fun pickServer() {
        if (store.hasCustomServer()) return
        for (url in SessionStore.SERVER_CANDIDATES) {
            val ok = kotlinx.coroutines.withTimeoutOrNull(6_000) {
                runCatching {
                    httpClient.get(url + "/api/ping").status.value == 200
                }.getOrDefault(false)
            } ?: false
            if (ok) {
                if (store.snapshot().serverUrl != url) store.setAutoServer(url)
                return
            }
        }
    }

    /** Asks Firebase for the push token (only if google-services.json was added to the build). */
    fun registerPush() {
        try {
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) return
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                if (!t.isNullOrEmpty()) appScope.launch { chatRepository.registerPushToken(t) }
            }
        } catch (e: Exception) {
            // Firebase is not configured — messages still arrive while the app/service is running
        }
    }
}
