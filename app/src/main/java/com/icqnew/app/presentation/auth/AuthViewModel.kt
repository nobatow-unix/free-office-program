package com.icqnew.app.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icqnew.app.data.remote.ApiException
import com.icqnew.app.domain.AuthRepository
import com.icqnew.app.domain.VerifyResult
import com.icqnew.app.presentation.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the user signs in. */
enum class LoginMethod { PHONE, EMAIL, PASSWORD, QR }

/** INPUT = phone / e-mail / login+password / QR; SETUP = new account: @username + password («Далее»). */
enum class AuthStep { INPUT, CODE, NAME, SETUP }

data class AuthUiState(
    val method: LoginMethod = LoginMethod.PHONE,
    val step: AuthStep = AuthStep.INPUT,
    val phone: String = "",
    val email: String = "",
    val login: String = "",
    val password: String = "",
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
    /** text inside the QR code shown on this phone */
    val qr: String = "",
    // SETUP
    val newUsername: String = "",
    val usernameHint: String? = null,
    val newPassword: String = "",
    val newPassword2: String = "",
)

/** Sign in: phone + code, e-mail + code, ID/@username + password, or a QR code approved on another device. */
class AuthViewModel(
    private val auth: AuthRepository,
    private val detectDial: suspend (sim: String) -> String = { "" },
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var dialAsked = false
    private var qrJob: Job? = null
    private var usernameJob: Job? = null

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

    fun setMethod(m: LoginMethod) {
        _state.update { it.copy(method = m, step = AuthStep.INPUT, error = null, code = "") }
        if (m == LoginMethod.QR) startQr() else { qrJob?.cancel(); qrJob = null }
    }

    fun onPhone(v: String) = _state.update { it.copy(phone = v, error = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v.trim().take(120), error = null) }
    fun onLogin(v: String) = _state.update { it.copy(login = v.trim().take(120), error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v.take(128), error = null) }
    fun onName(v: String) = _state.update { it.copy(name = v, error = null) }
    fun onNewPassword(v: String) = _state.update { it.copy(newPassword = v.take(128), error = null) }
    fun onNewPassword2(v: String) = _state.update { it.copy(newPassword2 = v.take(128), error = null) }

    fun onNewUsername(v: String) {
        val u = v.trim().removePrefix("@").take(32)
        _state.update { it.copy(newUsername = u, usernameHint = null, error = null) }
        usernameJob?.cancel()
        if (u.isEmpty()) return
        usernameJob = viewModelScope.launch {
            delay(400)
            val r = runCatching { auth.checkUsername(u) }.getOrNull()
            _state.update { if (it.newUsername == u) it.copy(usernameHint = r ?: "✔ Имя свободно") else it }
        }
    }

    fun onCode(v: String) {
        val c = v.filter { it.isDigit() }.take(6)
        _state.update { it.copy(code = c, error = null) }
        if (c.length == 6) submitCode()
    }

    fun sendCode() {
        val s = _state.value
        if (s.loading) return
        when (s.method) {
            LoginMethod.PHONE -> if (s.phone.count { it.isDigit() } < 9) return fail("Введите номер телефона с кодом страны")
            LoginMethod.EMAIL -> if (!EMAIL.matches(s.email)) return fail("Введите адрес почты, например name@gmail.com")
            else -> return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val inApp = if (s.method == LoginMethod.EMAIL) auth.requestEmailCode(s.email) else auth.requestCode(s.phone)
                _state.update { it.copy(step = AuthStep.CODE, code = "", loading = false, resendIn = 30, codeInApp = inApp) }
                countdown()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    private suspend fun verify(name: String?): VerifyResult {
        val s = _state.value
        return if (s.method == LoginMethod.EMAIL) auth.verifyEmail(s.email, s.code, name) else auth.verify(s.phone, s.code, name)
    }

    private fun handle(r: VerifyResult) {
        when (r) {
            is VerifyResult.NeedName -> _state.update { it.copy(loading = false, step = AuthStep.NAME) }
            is VerifyResult.Done -> _state.update {
                if (r.isNew) it.copy(loading = false, step = AuthStep.SETUP) else it.copy(loading = false, done = true)
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
                handle(verify(null))
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
                when (val r = verify(s.name)) {
                    is VerifyResult.NeedName -> _state.update { it.copy(loading = false, error = "Не удалось создать аккаунт") }
                    is VerifyResult.Done -> handle(r.copy(isNew = true))
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun passwordLogin() {
        val s = _state.value
        if (s.loading) return
        if (s.login.isBlank()) return fail("Введите ID, @имя или почту")
        if (s.password.isEmpty()) return fail("Введите пароль")
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                handle(auth.passwordLogin(s.login, s.password))
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    /** «Далее» after a new account: saves @username and password (both can be skipped). */
    fun finishSetup(skip: Boolean = false) {
        val s = _state.value
        if (s.loading) return
        if (skip) return _state.update { it.copy(done = true) }
        if (s.newPassword.isNotEmpty() && s.newPassword.length < 6) return fail("Пароль — от 6 символов")
        if (s.newPassword != s.newPassword2) return fail("Пароли не совпадают")
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (s.newUsername.isNotBlank()) auth.setUsername(s.newUsername)
                if (s.newPassword.isNotEmpty()) auth.setPassword(s.newPassword, null)
                _state.update { it.copy(loading = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    /** Shows a QR here; another device where the account is open scans it (Аккаунт → Сканировать QR). */
    private fun startQr() {
        qrJob?.cancel()
        qrJob = viewModelScope.launch {
            while (true) {
                val (code, text) = try {
                    auth.qrStart()
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.userMessage(), qr = "") }
                    delay(5_000)
                    continue
                }
                _state.update { it.copy(qr = text, error = null) }
                val started = System.currentTimeMillis()
                while (System.currentTimeMillis() - started < 280_000) {
                    delay(2_000)
                    val r = try {
                        auth.qrPoll(code)
                    } catch (e: ApiException) {
                        break // expired: make a new one
                    } catch (e: Exception) {
                        null
                    }
                    if (r != null) {
                        handle(r)
                        return@launch
                    }
                }
            }
        }
    }

    fun back() {
        _state.update {
            when (it.step) {
                AuthStep.NAME -> it.copy(step = AuthStep.CODE, error = null)
                AuthStep.CODE -> it.copy(step = AuthStep.INPUT, error = null, code = "")
                AuthStep.SETUP -> it.copy(done = true)
                AuthStep.INPUT -> it
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

    private companion object {
        val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[A-Za-z]{2,24}$""")
    }
}
