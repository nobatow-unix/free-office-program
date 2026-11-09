package com.icqnew.app

import android.app.Application
import com.icqnew.app.di.AppContainer
import kotlinx.coroutines.launch

class IcqApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.icqnew.app.presentation.emoji.Emoji.init(this)
        container = AppContainer(this)
        container.startIfLoggedIn()
        container.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { container.mediaCache.trim() }
        // "Online" is shown only while the app is actually on the screen.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> container.chatRepository.setAppActive(true)
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> container.chatRepository.setAppActive(false)
                    else -> Unit
                }
            }
        )
    }
}
