@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.icqnew.app

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.icqnew.app.data.call.CallPhase
import com.icqnew.app.data.notify.ConnectionService
import com.icqnew.app.data.notify.Notifier
import com.icqnew.app.domain.AppSettings
import com.icqnew.app.domain.ThemeMode
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.call.CallScreen
import com.icqnew.app.presentation.components.LocalServerUrl
import com.icqnew.app.presentation.navigation.AppNav
import com.icqnew.app.presentation.navigation.Routes
import com.icqnew.app.presentation.theme.IcqTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer()
        handleIntent(intent)

        // "Block screenshots" privacy option.
        lifecycleScope.launch {
            container.store.snapshots.collect { s ->
                if (s.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        // During a call the app may show above the lock screen and keeps the screen on.
        lifecycleScope.launch {
            container.callManager.state.collect { s ->
                val on = s != null && s.phase != CallPhase.ENDED
                if (Build.VERSION.SDK_INT >= 27) {
                    setShowWhenLocked(on)
                    setTurnScreenOn(on)
                }
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        setContent {
            val app by container.settingsRepository.app.collectAsStateWithLifecycle(AppSettings())
            val dark = when (app.theme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Status / navigation bar icons follow the chosen theme.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            IcqTheme(dark = dark, accent = app.accent, fontScale = app.fontScale) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    val server by produceState("") {
                        container.store.snapshots.collect { value = it.serverUrl }
                    }
                    val start by produceState<String?>(initialValue = null) {
                        val hasToken = container.store.snapshot().token != null
                        if (hasToken) ConnectionService.start(ctx)
                        value = if (hasToken) Routes.HOME else Routes.LOGIN
                    }
                    val storyGroups by container.chatRepository.stories.collectAsStateWithLifecycle()
                    val myUin by produceState(0L) { container.store.snapshots.collect { value = it.uin } }
                    val rings = remember(storyGroups, myUin) {
                        storyGroups.filter { it.user.uin != myUin && it.stories.isNotEmpty() }
                            .associate { it.user.uin to (if (it.allSeen) 2 else 1) }
                    }
                    CompositionLocalProvider(
                        LocalServerUrl provides server,
                        com.icqnew.app.presentation.components.LocalStoryRings provides rings,
                    ) {
                        val callUi by container.callManager.state.collectAsStateWithLifecycle()
                        val callFolded by container.callManager.minimized.collectAsStateWithLifecycle()
                        val voiceUi by container.voicePlayer.state.collectAsStateWithLifecycle()
                        val homeShown by container.homeOnScreen.collectAsStateWithLifecycle()
                        val barsShown = !homeShown && voiceUi.playingId != null ||
                            !homeShown &&
                            (callFolded && callUi != null && callUi?.phase != com.icqnew.app.data.call.CallPhase.ENDED &&
                                callUi?.phase != com.icqnew.app.data.call.CallPhase.INCOMING)
                        Box(Modifier.fillMaxSize()) {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                                // «Вернуться к звонку» + mini player of a voice message (they take the status bar)
                                if (!homeShown) com.icqnew.app.presentation.components.AppTopBars(
                                    container.callManager, container.voicePlayer,
                                ) { peer -> container.openChatRequest.value = peer }
                                Box(
                                    Modifier.weight(1f).then(
                                        if (barsShown) Modifier.consumeWindowInsets(
                                            androidx.compose.foundation.layout.WindowInsets.statusBars,
                                        ) else Modifier
                                    ),
                                ) {
                                    start?.let { AppNav(it, container.openChatRequest, container.openLinkRequest) }
                                }
                            }
                            CallScreen(container.callManager)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A tap on a message notification opens that chat; an incoming-call tap just brings the app up. */
    private fun handleIntent(intent: Intent?) {
        // a link to a person / channel (website button or https://icq-new.online/@name)
        intent?.data?.toString()?.let { link ->
            if (com.icqnew.app.presentation.IcqLinks.parse(link) != null) appContainer().openLinkRequest.value = link
            intent.data = null
        }
        val peer = intent?.getLongExtra(Notifier.EXTRA_PEER, 0L) ?: 0L
        if (peer != 0L) {
            appContainer().openChatRequest.value = peer
            intent?.removeExtra(Notifier.EXTRA_PEER)
        }
        if (intent?.getBooleanExtra(Notifier.EXTRA_CALL, false) == true) {
            ConnectionService.start(this)
            intent.removeExtra(Notifier.EXTRA_CALL)
        }
    }
}
