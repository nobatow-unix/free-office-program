package com.icqnew.app.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.icqnew.app.IcqApp
import com.icqnew.app.di.AppContainer

fun Context.appContainer(): AppContainer = (applicationContext as IcqApp).container

/** Creates a ViewModel with access to the DI container (no Hilt needed). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.appContainer()
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(container) } },
    )
}

/** Turns any failure into a short Russian message for the UI. */
fun Throwable.userMessage(): String = when (this) {
    is com.icqnew.app.data.remote.ApiException -> message ?: "Ошибка сервера"
    else -> "Нет соединения с сервером"
}
