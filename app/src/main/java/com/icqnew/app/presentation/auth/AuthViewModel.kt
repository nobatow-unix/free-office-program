package com.icqnew.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icqnew.app.domain.RequestCodeUseCase
import com.icqnew.app.domain.VerifyCodeUseCase
import com.icqnew.app.domain.VerifyResult
import com.icqnew.app.presentation.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep { PHONE, CODE, NAME }

data class AuthUiState(
    val step: AuthStep = AuthStep.PHONE,
    val phone: String = "",
    val code: String = "",
    val name: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val resendIn: Int = 0,
    val done: Boolean = false,
    /** Country code that was detected automatically ("+993"). */
    val autoDial: String = "",
    /** the code went to the «ICQ» chat on another logged-in device */
    val codeInApp: Boolean = false,
)

/** Phone + one-time code login. The code is delivered to the user by the admin (SMS from his own number). */
class AuthViewModel(
    private val requestCode: RequestCodeUseCase,
    private val verifyCode: VerifyCodeUseCase,
    private val detectDial: suspend (sim: String) -> String = { "" },
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var dialAsked = false

    /** Fills in the country code (by IP, else by the SIM card) while the field is still empty. */
    fun autoDial(sim: String) {
        if (dialAsked) return
        dialAsked = true
        viewModelScope.launch {
            val dial = runCatching { detectDial(sim) }.getOrDefault("")
            if (dial.isNotBlank()) {
                _state.update { if (it.phone.isBlank()) it.copy(phone = "$dial ", autoDial = dial) else it }
            }
        }
    }

    fun onPhone(v: String) = _state.update { it.copy(phone = v, error = null) }
    fun onName(v: String) = _state.update { it.copy(name = v, error = null) }

    fun onCode(v: String) {
        val c = v.filter { it.isDigit() }.take(6)
        _state.update { it.copy(code = c, error = null) }
        if (c.length == 6) submitCode()
    }

    fun sendCode() {
        val s = _state.value
        if (s.loading) return
        if (s.phone.count { it.isDigit() } < 9) return fail("Введите номер телефона с кодом страны")
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val inApp = requestCode(s.phone)
                _state.update { it.copy(step = AuthStep.CODE, code = "", loading = false, resendIn = 30, codeInApp = inApp) }
                countdown()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun submitCode() {
        val s = _state.value
        if (s.loading) return
        if (s.code.length != 6) return fail("Введите 6 цифр кода")
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                when (verifyCode(s.phone, s.code, null)) {
                    is VerifyResult.NeedName -> _state.update { it.copy(loading = false, step = AuthStep.NAME) }
                    is VerifyResult.Done -> _state.update { it.copy(loading = false, done = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun submitName() {
        val s = _state.value
        if (s.loading) return
        if (s.name.isBlank()) return fail("Введите имя")
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                when (verifyCode(s.phone, s.code, s.name)) {
                    is VerifyResult.NeedName -> _state.update { it.copy(loading = false, error = "Не удалось создать аккаунт") }
                    is VerifyResult.Done -> _state.update { it.copy(loading = false, done = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun back() {
        _state.update {
            when (it.step) {
                AuthStep.NAME -> it.copy(step = AuthStep.CODE, error = null)
                AuthStep.CODE -> it.copy(step = AuthStep.PHONE, error = null, code = "")
                AuthStep.PHONE -> it
            }
        }
    }

    private fun countdown() {
        viewModelScope.launch {
            while (_state.value.resendIn > 0) {
                delay(1_000)
                _state.update { it.copy(resendIn = (it.resendIn - 1).coerceAtLeast(0)) }
            }
        }
    }

    private fun fail(msg: String) = _state.update { it.copy(error = msg) }
}
