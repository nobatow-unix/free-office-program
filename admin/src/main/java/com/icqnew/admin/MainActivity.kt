package com.icqnew.admin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF00CB66)
private val Red = Color(0xFFFF5A5F)
private val Orange = Color(0xFFFFA726)

private val scheme = darkColorScheme(
    primary = Green, onPrimary = Color.White,
    primaryContainer = Color(0xFF15402B), onPrimaryContainer = Color(0xFFBFF5D8),
    background = Color(0xFF171717), onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF171717), onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFA6A6A6),
    surfaceContainer = Color(0xFF212121), surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF323232), surfaceContainerLow = Color(0xFF1C1C1C),
    outline = Color(0xFF4A4A4A), outlineVariant = Color(0xFF333333), error = Red,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

private fun toast(ctx: android.content.Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

private fun ago(ms: Long): String {
    if (ms <= 0) return "—"
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "только что"
        s < 3600 -> "${s / 60} мин назад"
        s < 86400 -> "${s / 3600} ч назад"
        else -> SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

@Composable
private fun Root() {
    var token by remember { mutableStateOf(Core.prefs.token) }
    if (token == null) LoginScreen { token = Core.prefs.token } else MainScreen { token = null }
}

// ================================================================== login
@Composable
private fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Core.prefs.server) }
    var login by remember { mutableStateOf(Core.prefs.login) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(84.dp).clip(CircleShape).background(Color(0xFF7A0E1E)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.AdminPanelSettings, null, tint = Color.White, modifier = Modifier.size(46.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("ICQ Admin", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Для администраторов ICQ New", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(server, { server = it.trim() }, label = { Text("Сервер") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(login, { login = it.trim() }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = !busy && login.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val device = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL
                        val r = Core.api.login(server.ifBlank { "https://icq-new.online" }, login, password, device)
                        Core.prefs.token = r.token
                        Core.prefs.login = r.admin.login
                        Core.prefs.role = r.admin.role
                        Core.prefs.perms = r.admin.perms.toSet()
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: "Нет связи с сервером"
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Войти", fontSize = 17.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Супер-админ: логин admin и пароль админ-панели.\nОстальным админам логин и права выдаёт супер-админ.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

// ================================================================== main
private data class Tab(val key: String, val title: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(onLoggedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<MeResp?>(null) }
    var perms by remember { mutableStateOf(Core.prefs.perms) }
    val isSuper = (me?.admin?.isSuper) ?: (Core.prefs.role == "super")
    fun can(p: String) = isSuper || p in perms
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val m = Core.api.me()
                me = m
                perms = m.admin.perms.toSet()
                Core.prefs.perms = perms
                Core.prefs.role = m.admin.role
            } catch (e: ApiError) {
                if (e.code == 401) {
                    Core.prefs.token = null
                    AutoSmsService.stop(ctx)
                    onLoggedOut()
                    return@LaunchedEffect
                }
            } catch (_: Exception) {
            }
            delay(15_000)
        }
    }
    val tabs = buildList {
        if (can("sms")) add(Tab("codes", "Коды", Icons.Filled.Sms))
        if (can("users") || can("verify")) add(Tab("users", "Люди", Icons.Filled.People))
        if (can("groups") || can("verify")) add(Tab("groups", "Группы", Icons.Filled.Groups))
        if (can("balance")) add(Tab("balance", "Баланс", Icons.Filled.AccountBalanceWallet))
        add(Tab("more", "Ещё", Icons.Filled.MoreHoriz))
    }
    var tab by rememberSaveable { mutableStateOf(tabs.first().key) }
    if (tabs.none { it.key == tab }) tab = tabs.first().key
    var sub by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sub?.let { subTitle(it) } ?: "ICQ Admin", fontWeight = FontWeight.Bold)
                        Text(
                            (me?.admin?.login ?: Core.prefs.login) + " · " + if (isSuper) "супер-админ" else "админ",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (sub != null) IconButton(onClick = { sub = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            AutoSmsService.stop(ctx)
                            Core.api.logout()
                            Core.prefs.token = null
                            onLoggedOut()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.Logout, "Выйти") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.key && sub == null,
                        onClick = { tab = t.key; sub = null },
                        icon = { Icon(t.icon, t.title) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (sub ?: tab) {
                "codes" -> CodesScreen(me)
                "users" -> UsersScreen(canUsers = can("users"), canVerify = can("verify"), canBalance = can("balance"))
                "groups" -> GroupsScreen(canVerify = can("verify"))
                "balance" -> BalanceScreen()
                "fragment" -> FragmentScreen()
                "servers" -> ServersScreen()
                "admins" -> AdminsScreen()
                "log" -> LogScreen()
                else -> MoreScreen(me, isSuper, ::can) { sub = it }
            }
        }
    }
}

private fun subTitle(k: String) = when (k) {
    "fragment" -> "Fragment"
    "servers" -> "Серверы"
    "admins" -> "Администраторы"
    "log" -> "Журнал действий"
    else -> "ICQ Admin"
}

// ================================================================== codes + automatic SMS
@Composable
private fun CodesScreen(me: MeResp?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val auto by AutoSmsService.state.collectAsStateWithLifecycle()
    var autoOn by remember { mutableStateOf(Core.prefs.autoSms) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var sims by remember { mutableStateOf(Sms.sims(ctx)) }
    var simId by remember { mutableIntStateOf(Core.prefs.simId) }
    var sending by remember { mutableStateOf<String?>(null) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        sims = Sms.sims(ctx)
        if (res[Manifest.permission.SEND_SMS] == true && autoOn) AutoSmsService.start(ctx)
        else if (res[Manifest.permission.SEND_SMS] == false) {
            autoOn = false; Core.prefs.autoSms = false
            toast(ctx, "Без разрешения на SMS коды не отправить")
        }
    }
    fun askPerms() {
        val need = buildList {
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { if (autoOn) AutoSmsService.start(ctx) } else perms.launch(need.toTypedArray())
    }
    LaunchedEffect(Unit) {
        if (Core.prefs.autoSms && Sms.canSend(ctx) && !AutoSmsService.state.value.running) AutoSmsService.start(ctx)
    }
    LaunchedEffect(reload) {
        while (true) {
            runCatching { Core.api.pending() }.onSuccess { pending = it }
            delay(4_000)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SmartToy, null, tint = if (auto.running) Green else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Авто-SMS с этого телефона", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                if (auto.running) auto.status.ifBlank { "Работает" } else "Выключено",
                                fontSize = 13.sp, color = if (auto.running) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoOn, onCheckedChange = { on ->
                            autoOn = on
                            if (on) { Core.prefs.autoSms = true; askPerms() } else AutoSmsService.stop(ctx)
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Кто бы ни запросил код по номеру — телефон сам отправит ему SMS со своей SIM-карты. " +
                            "Работает в фоне и после перезагрузки. Каждый код уходит только с одного телефона.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (auto.error.isNotBlank()) Text(auto.error, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Сегодня отправлено с этого телефона: ${Core.prefs.sentToday}", fontSize = 13.sp)
                    if (sims.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text("SIM-карта для SMS:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = simId < 0, onClick = { simId = -1; Core.prefs.simId = -1 }, label = { Text("По умолчанию") })
                            sims.forEach { s ->
                                FilterChip(selected = simId == s.id, onClick = { simId = s.id; Core.prefs.simId = s.id },
                                    label = { Text(s.label, maxLines = 1) })
                            }
                        }
                    }
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    if (autoOn && pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                        TextButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                            }
                        }) { Text("Разрешить работу в фоне (экономия батареи)") }
                    }
                }
            }
        }
        val others = me?.autoSms.orEmpty()
        if (others.isNotEmpty()) item {
            Text(
                "Авто-SMS включено: " + others.joinToString { it.login + if (it.device.isNotBlank()) " (${it.device})" else "" },
                fontSize = 13.sp, color = Green, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            val p = pending
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Запросы кода", fontWeight = FontWeight.Bold, color = Green, modifier = Modifier.weight(1f))
                if (p != null) Text("людей: ${p.users} · онлайн: ${p.online}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val items = pending?.items.orEmpty()
        if (pending == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (items.isEmpty()) item { Text("Пока никто не запрашивал код", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
        items(items, key = { it.phone + it.code }) { c ->
            val status = when {
                c.email -> "почта" to MaterialTheme.colorScheme.onSurfaceVariant
                c.sent -> ("отправлен" + if (c.by.isNotBlank()) " · ${c.by}" else "") to Green
                c.expired -> "истёк" to MaterialTheme.colorScheme.onSurfaceVariant
                c.sending -> "отправляется…" to Orange
                c.error.isNotBlank() -> "ошибка: ${c.error}" to Red
                else -> "ждёт SMS" to Orange
            }
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (c.email) c.phone.removePrefix("mail:") else "+" + c.phone, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(c.code, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
                        Text("${status.first} · ${c.age / 60} мин ${c.age % 60} с назад", fontSize = 12.sp, color = status.second,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(c.code)); toast(ctx, "Код скопирован") }) {
                        Icon(Icons.Filled.ContentCopy, "Копировать")
                    }
                    if (!c.email && !c.expired) {
                        FilledTonalButton(
                            enabled = sending == null,
                            onClick = {
                                if (!Sms.canSend(ctx)) { askPerms(); return@FilledTonalButton }
                                sending = c.phone
                                scope.launch {
                                    val err = Sms.send(ctx, "+" + c.phone, "ICQ New: ${c.code} — код для входа. Никому не сообщайте его.", Core.prefs.simId)
                                    if (err == null) {
                                        runCatching { Core.api.markSent(c.phone) }
                                        Core.prefs.sentToday = Core.prefs.sentToday + 1
                                        toast(ctx, "SMS отправлено")
                                    } else toast(ctx, err)
                                    sending = null
                                    reload++
                                }
                            },
                        ) {
                            if (sending == c.phone) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(if (c.sent) "Ещё раз" else "SMS")
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== users
@Composable
private fun UsersScreen(canUsers: Boolean, canVerify: Boolean, canBalance: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<UserRow>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<UserRow?>(null) }
    LaunchedEffect(q, reload) {
        delay(350)
        if (!canUsers) { list = emptyList(); return@LaunchedEffect }
        list = runCatching { Core.api.users(q) }.getOrElse { toast(ctx, it.message ?: "Ошибка"); emptyList() }
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(q, { q = it }, if (canUsers) "Имя, телефон или ID" else "Нет права на список людей")
        if (!canUsers && canVerify) VerifyById("user")
        LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            items(list.orEmpty(), key = { it.uin }) { u ->
                ListCard(onClick = { open = u }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (u.online) Green else Color(0xFF555555)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(u.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (u.verified) Icon(Icons.Filled.Verified, null, tint = Green, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                                if (u.banned) Text("  заблокирован", color = Red, fontSize = 12.sp)
                            }
                            Text("ID ${u.uin}" + (u.username?.let { " · @$it" } ?: "") + (if (u.phone.isNotBlank() && !u.phone.startsWith("mail:")) " · +${u.phone}" else ""),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text("сообщений: ${u.messages} · был(а): ${if (u.online) "в сети" else ago(u.lastSeen)}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    open?.let { u ->
        var confirmDelete by remember { mutableStateOf(false) }
        var amount by remember { mutableStateOf("") }
        fun act(block: suspend () -> Unit, done: String) {
            scope.launch {
                runCatching { block() }.onSuccess { toast(ctx, done); reload++; open = null }.onFailure { toast(ctx, it.message ?: "Ошибка") }
            }
        }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(u.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ID ${u.uin}" + (u.username?.let { " · @$it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (canVerify) ActionRow(Icons.Filled.Verified, if (u.verified) "Снять зелёную галочку" else "Дать зелёную галочку", Green) {
                        act({ Core.api.verify("user", u.uin, !u.verified) }, "Готово")
                    }
                    if (canUsers) ActionRow(Icons.Filled.Block, if (u.banned) "Разблокировать" else "Заблокировать", Orange) {
                        act({ Core.api.ban(u.uin, !u.banned) }, if (u.banned) "Разблокирован" else "Заблокирован")
                    }
                    if (canBalance) Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '-' }.take(7) }, singleLine = true,
                            label = { Text("Gold") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextButton(enabled = amount.toIntOrNull()?.let { it != 0 } == true, onClick = {
                            act({ Core.api.topUp(u.uin.toString(), amount.toInt(), "Пополнение (администратор)") }, "Баланс изменён")
                        }) { Text("Пополнить") }
                    }
                    if (canUsers) ActionRow(Icons.Filled.DeleteForever, "Удалить аккаунт", Red) { confirmDelete = true }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Закрыть") } },
        )
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить ${u.name}?") },
            text = { Text("Аккаунт, его сообщения и файлы будут удалены навсегда.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; act({ Core.api.deleteUser(u.uin) }, "Удалён") }) { Text("Удалить", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun VerifyById(kind: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(id, { id = it.filter { c -> c.isDigit() } }, singleLine = true, label = { Text("ID для галочки") },
            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        TextButton(enabled = id.isNotBlank(), onClick = {
            scope.launch { runCatching { Core.api.verify(kind, id.toLong(), true) }.onSuccess { toast(ctx, "Галочка выдана") }.onFailure { toast(ctx, it.message ?: "Ошибка") } }
        }) { Text("Дать") }
        TextButton(enabled = id.isNotBlank(), onClick = {
            scope.launch { runCatching { Core.api.verify(kind, id.toLong(), false) }.onSuccess { toast(ctx, "Галочка снята") }.onFailure { toast(ctx, it.message ?: "Ошибка") } }
        }) { Text("Снять") }
    }
}

// ================================================================== groups
@Composable
private fun GroupsScreen(canVerify: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<GroupRow>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(q, reload) {
        delay(350)
        list = runCatching { Core.api.groups(q) }.getOrElse { emptyList() }
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(q, { q = it }, "Название, @имя или ID")
        if (list.isNullOrEmpty() && canVerify) VerifyById("group")
        LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.orEmpty(), key = { it.id }) { g ->
                ListCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (g.kind == "channel") Icons.Filled.Campaign else Icons.Filled.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (g.verified) Icon(Icons.Filled.Verified, null, tint = Green, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                            }
                            Text((if (g.kind == "channel") "канал" else "группа") + " · ID ${g.id} · ${g.count} уч." + (g.username?.let { " · @$it" } ?: ""),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (canVerify) TextButton(onClick = {
                            scope.launch {
                                runCatching { Core.api.verify("group", g.id, !g.verified) }
                                    .onSuccess { reload++ }.onFailure { toast(ctx, it.message ?: "Ошибка") }
                            }
                        }) { Text(if (g.verified) "Снять ✓" else "Дать ✓") }
                    }
                }
            }
        }
    }
}

// ================================================================== balance
@Composable
private fun BalanceScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var who by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var log by remember { mutableStateOf<List<BalanceRow>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { log = Core.api.balanceLog() }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Пополнить баланс (Gold)", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(who, { who = it.trim() }, singleLine = true, label = { Text("ID, телефон или @имя") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '-' }.take(7) }, singleLine = true,
                        label = { Text("Сумма (минус — списать)") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(reason, { reason = it.take(200) }, singleLine = true, label = { Text("Причина (необязательно)") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = who.isNotBlank() && amount.toIntOrNull()?.let { it != 0 } == true, onClick = {
                        scope.launch {
                            runCatching { Core.api.topUp(who, amount.toInt(), reason.ifBlank { "Пополнение (администратор)" }) }
                                .onSuccess { toast(ctx, "Готово"); amount = ""; reason = ""; reload++ }
                                .onFailure { toast(ctx, it.message ?: "Ошибка") }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Применить") }
                }
            }
        }
        item { Text("Последние операции", fontWeight = FontWeight.Bold, color = Green, modifier = Modifier.padding(4.dp)) }
        items(log) { b ->
            ListCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((b.name ?: "ID ${b.uin}"), fontWeight = FontWeight.Medium)
                        Text(b.reason + " · " + ago(b.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Text((if (b.delta > 0) "+" else "") + b.delta, fontWeight = FontWeight.Bold, color = if (b.delta >= 0) Green else Red)
                }
            }
        }
    }
}

// ================================================================== more
@Composable
private fun MoreScreen(me: MeResp?, isSuper: Boolean, can: (String) -> Boolean, open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(me?.admin?.login ?: Core.prefs.login, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (isSuper) "Супер-админ · все права" else "Админ", color = Green)
                Spacer(Modifier.height(6.dp))
                Text("Сервер: " + Core.prefs.server, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!isSuper) Text(
                    "Права: " + (me?.admin?.perms ?: Core.prefs.perms.toList()).joinToString { PERM_NAMES[it] ?: it },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                me?.let { Text("Пользователей: ${it.users} · онлайн: ${it.online} · ждут SMS: ${it.waitingSms}", fontSize = 13.sp) }
            }
        }
        if (can("fragment")) MenuItem(Icons.Filled.Diamond, "Fragment — @имена на продажу") { open("fragment") }
        if (isSuper) {
            MenuItem(Icons.Filled.Dns, "Серверы (VPS): нагрузка, память, диск") { open("servers") }
            MenuItem(Icons.Filled.AdminPanelSettings, "Администраторы и права") { open("admins") }
            MenuItem(Icons.Filled.History, "Журнал действий админов") { open("log") }
        }
    }
}

@Composable
private fun FragmentScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<LotRow>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { list = runCatching { Core.api.lots() }.getOrDefault(emptyList()) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Выставить @имя на продажу (можно 1–3 буквы)", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it.trim().removePrefix("@").take(32) }, singleLine = true, label = { Text("@имя") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(price, { price = it.filter { c -> c.isDigit() }.take(7) }, singleLine = true, label = { Text("Цена, Gold (0 — снять)") },
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(enabled = name.isNotBlank() && price.isNotBlank(), onClick = {
                        scope.launch {
                            runCatching { Core.api.setLot(name, price.toInt()) }
                                .onSuccess { toast(ctx, "Готово"); name = ""; price = ""; reload++ }
                                .onFailure { toast(ctx, it.message ?: "Ошибка") }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
                }
            }
        }
        items(list) { l ->
            ListCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("@" + l.username, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("🪙 ${l.price}  ")
                    Text(if (l.soldTo != null) "продано: " + (l.buyer ?: l.soldTo.toString()) else "в продаже",
                        fontSize = 12.sp, color = if (l.soldTo != null) MaterialTheme.colorScheme.onSurfaceVariant else Green)
                }
            }
        }
    }
}

private fun gb(b: Long) = if (b <= 0) "—" else if (b >= 1L shl 30) "%.1f ГБ".format(b / 1073741824.0) else "%d МБ".format(b / 1048576)
private fun speed(b: Long) = if (b >= 1 shl 20) "%.1f МБ/с".format(b / 1048576.0) else "%d КБ/с".format(b / 1024)
private fun uptime(s: Long) = when {
    s <= 0 -> "—"
    s >= 86400 -> "${s / 86400} дн ${(s % 86400) / 3600} ч"
    else -> "${s / 3600} ч ${(s % 3600) / 60} мин"
}

@Composable
private fun Meter(label: String, value: String, frac: Float) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val f = frac.coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = when { f > 0.9f -> Red; f > 0.7f -> Orange; else -> Green },
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/** Super admin: every VPS of the cluster — alive or not, CPU, RAM, disk, network, uptime. */
@Composable
private fun ServersScreen() {
    var info by remember { mutableStateOf<ClusterResp?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { Core.api.cluster() }.onSuccess { info = it; error = null }.onFailure { error = it.message }
            delay(5_000)
        }
    }
    val i = info
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                if (i == null) (error ?: "Загрузка…")
                else (if (i.enabled) "Кластер: ${i.nodes.size} серв." else "Один сервер") +
                    " · база ${i.db} ${gb(i.dbSize)} · онлайн: ${i.online}",
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp),
            )
        }
        items(i?.nodes.orEmpty(), key = { it.id }) { n ->
            val st = n.stats
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (n.alive) Green else Red))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.id + (if (i?.leader == n.id) " · лидер" else ""), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(n.url.removePrefix("http://") + (if (st.host.isNotBlank()) " · ${st.host}" else ""),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (n.alive) "работает" else "НЕ РАБОТАЕТ", color = if (n.alive) Green else Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (st.memTotal == 0L) {
                        Text("Нет данных о сервере", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Meter("Процессор (${st.cores} ядер)", (st.cpu?.let { "%.0f%%".format(it) } ?: "—") +
                            (st.load.firstOrNull()?.let { " · нагрузка %.2f".format(it) } ?: ""), ((st.cpu ?: 0.0) / 100).toFloat())
                        Meter("Память", "${gb(st.memUsed)} из ${gb(st.memTotal)}", st.memUsed.toFloat() / st.memTotal)
                        if (st.swapTotal > 0) Meter("Swap", "${gb(st.swapUsed)} из ${gb(st.swapTotal)}", st.swapUsed.toFloat() / st.swapTotal)
                        if (st.diskTotal > 0) Meter("Диск", "${gb(st.diskUsed)} из ${gb(st.diskTotal)}", st.diskUsed.toFloat() / st.diskTotal)
                        Spacer(Modifier.height(4.dp))
                        Text("Сеть: ↓ ${speed(st.netRx)} · ↑ ${speed(st.netTx)}  (всего ↓ ${gb(st.netRxTotal)} ↑ ${gb(st.netTxTotal)})", fontSize = 13.sp)
                        Text("Онлайн здесь: ${n.online} · подключений: ${st.connections} · файлы: ${gb(st.filesBytes)}", fontSize = 13.sp)
                        Text("Работает без перезагрузки: ${uptime(st.uptime)} · программа: ${gb(st.appMem)}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (n.alive && st.ts > 0 && System.currentTimeMillis() - st.ts > 60_000) {
                            Text("данные обновлены ${ago(st.ts)}", fontSize = 12.sp, color = Orange)
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== admins (super admin)
@Composable
private fun AdminsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<AdminDto>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<AdminDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { list = runCatching { Core.api.admins() }.getOrElse { toast(ctx, it.message ?: "Ошибка"); emptyList() } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Супер-админ (admin) может всё. Админам выдавайте только нужные права.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
            }
            if (list?.isEmpty() == true) item { Text("Админов пока нет. Нажмите «+».", modifier = Modifier.padding(16.dp)) }
            items(list.orEmpty(), key = { it.id }) { a ->
                ListCard(onClick = { edit = a }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (a.isSuper) Icons.Filled.Shield else Icons.Filled.Person, null,
                            tint = if (!a.enabled) Red else if (a.isSuper) Orange else Green)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.login + if (!a.enabled) " · выключен" else "", fontWeight = FontWeight.SemiBold)
                            Text(if (a.isSuper) "супер-админ" else a.perms.joinToString { PERM_NAMES[it] ?: it }.ifBlank { "без прав" },
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            Text("был: ${ago(a.lastSeen)}" + (if (a.device.isNotBlank()) " · ${a.device}" else "") +
                                (if (a.autoSms) " · авто-SMS" else "") + " · SMS: ${a.smsSent}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            containerColor = Green) { Icon(Icons.Filled.Add, "Добавить админа", tint = Color.White) }
    }
    if (creating) AdminDialog(null, onDismiss = { creating = false }) { body ->
        scope.launch {
            runCatching { Core.api.createAdmin(body) }
                .onSuccess { toast(ctx, "Админ ${it.login} создан"); creating = false; reload++ }
                .onFailure { toast(ctx, it.message ?: "Ошибка") }
        }
    }
    edit?.let { a ->
        AdminDialog(a, onDismiss = { edit = null }, onDelete = {
            scope.launch { runCatching { Core.api.deleteAdmin(a.id) }.onSuccess { edit = null; reload++ } }
        }) { body ->
            scope.launch {
                runCatching { Core.api.editAdmin(a.id, body) }
                    .onSuccess { toast(ctx, "Сохранено"); edit = null; reload++ }
                    .onFailure { toast(ctx, it.message ?: "Ошибка") }
            }
        }
    }
}

@Composable
private fun AdminDialog(a: AdminDto?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (AdminBody) -> Unit) {
    var login by remember { mutableStateOf(a?.login ?: "") }
    var password by remember { mutableStateOf("") }
    var isSuper by remember { mutableStateOf(a?.isSuper == true) }
    var enabled by remember { mutableStateOf(a?.enabled ?: true) }
    val perms = remember { mutableStateListOf<String>().apply { addAll(a?.perms ?: listOf("sms")) } }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (a == null) "Новый админ" else a.login) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (a == null) OutlinedTextField(login, { login = it.trim().take(32) }, singleLine = true, label = { Text("Логин") })
                OutlinedTextField(password, { password = it.take(64) }, singleLine = true,
                    label = { Text(if (a == null) "Пароль (от 6 символов)" else "Новый пароль (если нужно)") })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { isSuper = !isSuper }) {
                    Checkbox(isSuper, { isSuper = it })
                    Text("Супер-админ (все права + админы)")
                }
                if (!isSuper) {
                    Text("Права:", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    PERM_NAMES.forEach { (k, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            if (k in perms) perms.remove(k) else perms.add(k)
                        }) {
                            Checkbox(k in perms, { if (it) perms.add(k) else perms.remove(k) })
                            Text(v + if (k == "sms") " (и авто-SMS)" else "")
                        }
                    }
                }
                if (a != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { enabled = !enabled }) {
                    Switch(enabled, { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (enabled) "Включён" else "Выключен (не может войти)")
                }
                if (onDelete != null) TextButton(onClick = { confirm = true }) { Text("Удалить админа", color = Red) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (a != null || (login.length >= 3 && password.length >= 6)) && (password.isEmpty() || password.length >= 6),
                onClick = {
                    onSave(
                        AdminBody(
                            login = if (a == null) login else null,
                            password = password.ifBlank { null },
                            role = if (isSuper) "super" else "admin",
                            perms = perms.toList(),
                            enabled = if (a != null) enabled else null,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (confirm && onDelete != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Удалить ${a?.login}?") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Удалить", color = Red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
    )
}

@Composable
private fun LogScreen() {
    var list by remember { mutableStateOf<List<LogRow>?>(null) }
    LaunchedEffect(Unit) { list = runCatching { Core.api.log() }.getOrDefault(emptyList()) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (list == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        items(list.orEmpty()) { l ->
            ListCard {
                Column {
                    Text(l.login, fontWeight = FontWeight.SemiBold, color = Green, fontSize = 13.sp)
                    Text(l.action)
                    Text(ago(l.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ================================================================== small parts
@Composable
private fun SearchField(v: String, on: (String) -> Unit, hint: String) {
    OutlinedTextField(
        v, on, singleLine = true, placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun ListCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
    ) { content() }
}

@Composable
private fun ActionRow(icon: ImageVector, text: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp)
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListCard(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Green)
            Spacer(Modifier.width(14.dp))
            Text(text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
