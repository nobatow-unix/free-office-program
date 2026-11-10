package com.icqnew.app.presentation.auth

import androidx.activity.compose.BackHandler
import android.accounts.AccountManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.presentation.appViewModel
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.components.IcqLogo

@Composable
fun LoginScreen(onAuthenticated: () -> Unit) {
    val vm = appViewModel { c -> AuthViewModel(c.authRepository) { sim -> c.chatRepository.detectDialCode(sim) } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val container = ctx.appContainer()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val tm = ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val sim = runCatching { tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso }.getOrNull().orEmpty()
        vm.autoDial(sim)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    // accounts kept on this phone («Добавить аккаунт» was pressed): a way back
    val others by produceState(emptyList<com.icqnew.app.data.local.Accounts.Saved>()) {
        value = com.icqnew.app.data.local.Accounts.saved(ctx)
    }

    LaunchedEffect(s.done) { if (s.done) onAuthenticated() }
    BackHandler(enabled = s.step != AuthStep.INPUT) { vm.back() }

    // the phone's own account chooser: pick the e-mail address (Gmail etc.)
    val chooseEmail = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let { vm.onEmail(it) }
    }

    val shape = RoundedCornerShape(12.dp)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            IcqLogo(size = 84.dp)
            Spacer(Modifier.height(8.dp))
            Text("ICQ", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                when (s.step) {
                    AuthStep.INPUT -> when (s.method) {
                        LoginMethod.PHONE -> "Войдите по номеру телефона"
                        LoginMethod.EMAIL -> "Войдите по почте (Gmail и другие)"
                        LoginMethod.PASSWORD -> "Войдите по ID или @имени и паролю"
                        LoginMethod.QR -> "Вход по QR-коду"
                    }
                    AuthStep.CODE -> "Введите код"
                    AuthStep.NAME -> "Как вас зовут?"
                    AuthStep.SETUP -> "Почти готово"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            if (s.step == AuthStep.INPUT) {
                // method switch
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(4.dp),
                ) {
                    listOf(LoginMethod.PHONE to "Телефон", LoginMethod.EMAIL to "Почта", LoginMethod.PASSWORD to "Пароль", LoginMethod.QR to "QR")
                        .forEach { (m, label) ->
                            val sel = s.method == m
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable { vm.setMethod(m) }.padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                }
                Spacer(Modifier.height(18.dp))
            }

            when (s.step) {
                AuthStep.INPUT -> when (s.method) {
                    LoginMethod.PHONE -> PhoneInput(s, vm, shape)
                    LoginMethod.EMAIL -> {
                        OutlinedTextField(
                            value = s.email, onValueChange = vm::onEmail, singleLine = true, shape = shape,
                            label = { Text("Адрес почты") }, placeholder = { Text("name@gmail.com") },
                            leadingIcon = { Icon(Icons.Filled.AlternateEmail, null) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        TextButton(onClick = {
                            runCatching {
                                chooseEmail.launch(
                                    AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null),
                                )
                            }
                        }) { Text("Выбрать почту с телефона") }
                        Text(
                            "Мы отправим 6-значный код на эту почту.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                    LoginMethod.PASSWORD -> {
                        OutlinedTextField(
                            value = s.login, onValueChange = vm::onLogin, singleLine = true, shape = shape,
                            label = { Text("ID, @имя или почта") }, modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        PasswordField(s.password, vm::onPassword, "Пароль", shape)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Пароль задаётся в Настройках → Аккаунт. Нет пароля? Войдите по телефону или почте.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                    LoginMethod.QR -> {
                        Box(
                            Modifier.size(240.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bmp = remember(s.qr) { if (s.qr.isNotBlank()) qrBitmap(s.qr, 600) else null }
                            if (bmp != null) Image(bmp.asImageBitmap(), "QR-код", Modifier.fillMaxSize())
                            else CircularProgressIndicator()
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "1. Откройте ICQ New на другом телефоне, где вы уже вошли\n" +
                                "2. Профиль → Аккаунт → «Сканировать QR-код»\n3. Наведите камеру на этот код",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start,
                        )
                    }
                }
                AuthStep.CODE -> {
                    val target = if (s.method == LoginMethod.EMAIL) s.email else s.phone
                    Text(
                        when {
                            s.codeInApp -> "Мы отправили код в чат «ICQ» на вашем другом устройстве" +
                                if (s.method == LoginMethod.EMAIL) " и на почту $target." else "."
                            s.method == LoginMethod.EMAIL -> "Код отправлен на $target. Проверьте также папку «Спам»."
                            else -> "Код для $target скоро придёт сообщением от администратора. Обычно это занимает несколько минут."
                        },
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = s.code, onValueChange = vm::onCode, singleLine = true, shape = shape,
                        label = { Text("Код") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    // a copied code is pasted by itself
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    TextButton(onClick = {
                        val t = clipboard.getText()?.text.orEmpty().filter { it.isDigit() }
                        if (t.length >= 6) vm.onCode(t.take(6))
                    }) { Text("Вставить код из буфера") }
                }
                AuthStep.NAME -> {
                    OutlinedTextField(
                        value = s.name, onValueChange = vm::onName, singleLine = true, shape = shape,
                        label = { Text("Ваше имя") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                AuthStep.SETUP -> {
                    Text(
                        "Задайте имя пользователя и пароль — тогда можно входить по ID / @имени и паролю.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = s.newUsername, onValueChange = vm::onNewUsername, singleLine = true, shape = shape,
                        label = { Text("Имя пользователя") }, prefix = { Text("@") }, modifier = Modifier.fillMaxWidth(),
                    )
                    s.usernameHint?.let {
                        Text(it, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
                            color = if (it.startsWith("✔")) Color(0xFF22C55E) else MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    PasswordField(s.newPassword, vm::onNewPassword, "Пароль (от 6 символов)", shape)
                    Spacer(Modifier.height(8.dp))
                    PasswordField(s.newPassword2, vm::onNewPassword2, "Повторите пароль", shape)
                }
            }

            s.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            if (!(s.step == AuthStep.INPUT && s.method == LoginMethod.QR)) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        when (s.step) {
                            AuthStep.INPUT -> if (s.method == LoginMethod.PASSWORD) vm.passwordLogin() else vm.sendCode()
                            AuthStep.CODE -> vm.submitCode()
                            AuthStep.NAME -> vm.submitName()
                            AuthStep.SETUP -> vm.finishSetup()
                        }
                    },
                    shape = shape,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (s.loading) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(
                            when (s.step) {
                                AuthStep.INPUT -> if (s.method == LoginMethod.PASSWORD) "Войти" else "Получить код"
                                AuthStep.CODE -> "Войти"
                                AuthStep.NAME -> "Продолжить"
                                AuthStep.SETUP -> "Далее"
                            },
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (s.step == AuthStep.CODE) {
                if (s.resendIn > 0) {
                    Text(
                        "Отправить код ещё раз можно через ${s.resendIn} с",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    TextButton(onClick = vm::sendCode) { Text("Отправить код ещё раз") }
                }
            }
            when (s.step) {
                AuthStep.SETUP -> TextButton(onClick = { vm.finishSetup(skip = true) }) { Text("Пропустить") }
                AuthStep.INPUT -> {}
                else -> TextButton(onClick = vm::back) { Text("Назад") }
            }
            if (s.step == AuthStep.INPUT && others.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                others.forEach { a ->
                    TextButton(onClick = {
                        scope.launch { com.icqnew.app.data.local.Accounts.switchTo(ctx, container.store, a.uin) }
                    }) { Text("← Вернуться в аккаунт ${a.name}") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PasswordField(v: String, on: (String) -> Unit, label: String, shape: RoundedCornerShape) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = v, onValueChange = on, singleLine = true, shape = shape,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Скрыть" else "Показать")
            }
        },
    )
}

/** QR picture drawn on the phone (zxing). */
fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

@Composable
private fun PhoneInput(s: AuthUiState, vm: AuthViewModel, shape: RoundedCornerShape) {
    var country by remember { mutableStateOf<Country?>(null) }
    var local by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    // the code found by IP / SIM picks the flag automatically
    LaunchedEffect(s.autoDial) {
        if (country == null && s.autoDial.isNotBlank()) country = countryForNumber(s.autoDial)
    }
    LaunchedEffect(Unit) { if (country == null) countryForNumber(s.phone)?.let { country = it } }
    fun push() = vm.onPhone(((country?.code ?: "") + " " + local).trim())
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { picking = true }, shape = shape,
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.height(56.dp),
        ) {
            com.icqnew.app.presentation.emoji.EmojiImage(country?.flag ?: "🌐", 24.dp)
            Spacer(Modifier.width(6.dp))
            Text(country?.code ?: "+…", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = local,
            onValueChange = { v ->
                val clean = v.filter { it.isDigit() || it == ' ' || it == '+' }.take(18)
                if (clean.startsWith("+")) {
                    // full number pasted: take the country from it
                    countryForNumber(clean)?.let { c ->
                        country = c
                        local = clean.filter { it.isDigit() }.removePrefix(c.dial)
                    } ?: run { local = clean }
                } else local = clean
                push()
            },
            singleLine = true, shape = shape,
            label = { Text("Номер телефона") },
            placeholder = { Text("61 234567") },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
    country?.let {
        Text(
            it.name + if (s.autoDial.isNotBlank() && it.code == s.autoDial) " · определено автоматически" else "",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
        )
    }
    if (picking) {
        CountryPicker(
            onPick = { c -> country = c; picking = false; push() },
            onDismiss = { picking = false },
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Мы отправим код подтверждения. Его присылает администратор сообщением на ваш номер.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}


/** Searchable list of all countries with flags and codes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPicker(onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q) {
        val t = q.trim().lowercase().removePrefix("+")
        if (t.isEmpty()) COUNTRIES
        else COUNTRIES.filter { it.name.lowercase().contains(t) || it.dial.startsWith(t) || it.iso.lowercase() == t }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Страна", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Поиск страны или кода") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(list.size) { i ->
                    val c = list[i]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(c) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.icqnew.app.presentation.emoji.EmojiImage(c.flag, 28.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(c.name, modifier = Modifier.weight(1f), fontSize = 16.sp)
                        Text(c.code, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
