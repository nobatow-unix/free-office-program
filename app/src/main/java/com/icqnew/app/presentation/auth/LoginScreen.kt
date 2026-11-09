package com.icqnew.app.presentation.auth

import androidx.activity.compose.BackHandler
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
import com.icqnew.app.presentation.components.IcqLogo

@Composable
fun LoginScreen(onAuthenticated: () -> Unit) {
    val vm = appViewModel { c ->
        AuthViewModel(c.requestCodeUseCase, c.verifyCodeUseCase) { sim -> c.chatRepository.detectDialCode(sim) }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val tm = ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val sim = runCatching { tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso }.getOrNull().orEmpty()
        vm.autoDial(sim)
    }
    val s by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(s.done) { if (s.done) onAuthenticated() }
    BackHandler(enabled = s.step != AuthStep.PHONE) { vm.back() }

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
            Spacer(Modifier.height(48.dp))
            IcqLogo(size = 104.dp)
            Spacer(Modifier.height(12.dp))
            Text("ICQ", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                when (s.step) {
                    AuthStep.PHONE -> "Войдите по номеру телефона"
                    AuthStep.CODE -> "Введите код"
                    AuthStep.NAME -> "Как вас зовут?"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            when (s.step) {
                AuthStep.PHONE -> {
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
                AuthStep.CODE -> {
                    Text(
                        "Код для ${s.phone} скоро придёт сообщением от администратора. Обычно это занимает несколько минут.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = s.code, onValueChange = vm::onCode, singleLine = true, shape = shape,
                        label = { Text("Код из сообщения") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                AuthStep.NAME -> {
                    OutlinedTextField(
                        value = s.name, onValueChange = vm::onName, singleLine = true, shape = shape,
                        label = { Text("Ваше имя") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            s.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    when (s.step) {
                        AuthStep.PHONE -> vm.sendCode()
                        AuthStep.CODE -> vm.submitCode()
                        AuthStep.NAME -> vm.submitName()
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
                            AuthStep.PHONE -> "Получить код"
                            AuthStep.CODE -> "Войти"
                            AuthStep.NAME -> "Продолжить"
                        },
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
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
            if (s.step != AuthStep.PHONE) {
                TextButton(onClick = vm::back) { Text("Назад") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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
