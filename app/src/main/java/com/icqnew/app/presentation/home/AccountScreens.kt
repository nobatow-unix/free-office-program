package com.icqnew.app.presentation.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.data.remote.GiftDto
import com.icqnew.app.domain.User
import kotlinx.coroutines.launch
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.emoji.EmojiImage
import com.icqnew.app.presentation.emoji.EmojiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Gold = Color(0xFFFFB300)
private val Violet = Color(0xFF7C5CFF)

private fun dateOf(ms: Long): String = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(ms))

@Composable
private fun Card(title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) Text(
        title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 26.dp, top = 12.dp, bottom = 4.dp),
    ) else Spacer(Modifier.height(10.dp))
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = 2.dp),
        content = content,
    )
}

@Composable
private fun Row2(icon: ImageVector, title: String, value: String? = null, tint: Color? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint ?: MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f), color = tint ?: MaterialTheme.colorScheme.onSurface)
        if (value != null) Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (onClick != null) Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ================================================================== Premium

/** ICQ Premium: balance (topped up by the admin), plans, Premium for my chats, gifts. */
@Composable
fun PremiumPage(vm: HomeViewModel, onOpenChat: (Long) -> Unit) {
    val info by vm.premium.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    // refresh while the page is open: a top-up by the admin shows up by itself
    LaunchedEffect(Unit) {
        vm.loadPremium()
        while (true) {
            kotlinx.coroutines.delay(15_000)
            vm.loadPremium(silent = true)
        }
    }
    var buy by remember { mutableStateOf<Triple<Int, Int, Long?>?>(null) } // months, price, group
    var giftPremiumTo by remember { mutableStateOf<User?>(null) }
    var pickPremiumFriend by remember { mutableStateOf(false) }
    var gift by remember { mutableStateOf<GiftDto?>(null) }
    var topUp by remember { mutableStateOf(false) }
    var groupPick by remember { mutableStateOf(false) }

    val p = info
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val active = p.premiumUntil > System.currentTimeMillis()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // ---- header ----
        val t = rememberInfiniteTransition(label = "prem")
        val rot by t.animateFloat(-8f, 8f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "r")
        Column(
            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E88E5), Color(0xFF2AABEE), Color(0xFF7C5CFF))))
                .padding(vertical = 22.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.graphicsLayer { rotationZ = rot }) {
                Icon(Icons.Filled.Verified, null, tint = Color.White, modifier = Modifier.size(64.dp))
            }
            Text("ICQ Premium", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (active) "Подключён до ${dateOf(p.premiumUntil)}" else "Больше возможностей и синяя галочка у имени",
                color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center,
            )
        }
        // ---- balance ----
        Card("Баланс") {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AccountBalanceWallet, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("🪙 ${p.balance} ${p.currency}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Gold)
                    Text("на счёте", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary)
                        .clickable { topUp = true }.padding(horizontal = 16.dp, vertical = 9.dp),
                ) { Text("Пополнить", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
        // ---- plans ----
        Card(if (active) "Продлить Premium" else "Подключить Premium") {
            p.plans.forEach { pl ->
                Row2(Icons.Filled.Verified, monthsText(pl.months), "${pl.price} ${p.currency}", onClick = {
                    buy = Triple(pl.months, pl.price, null)
                })
            }
            Row2(Icons.Filled.CardGiftcard, "Подарить Premium другу", onClick = { pickPremiumFriend = true })
            Row2(Icons.Filled.Groups, "Premium для канала или группы", onClick = { groupPick = true })
        }
        // ---- perks ----
        Card("Что даёт Premium") {
            p.perks.forEach { k ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Violet.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when (k.icon) { "wallpaper" -> Icons.Filled.Wallpaper; "limits" -> Icons.Filled.RocketLaunch; "icon" -> Icons.Filled.Apps; else -> Icons.Filled.Verified },
                            null, tint = Violet, modifier = Modifier.size(19.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(k.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(k.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // ---- gifts ----
        Card("Подарки") {
            Text(
                "Подарите что-нибудь другу — или себе. Подарки видны в профиле.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp, 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                p.gifts.forEach { g ->
                    Column(
                        Modifier.width(84.dp).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { gift = g }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmojiImage(g.emoji, 40.dp)
                        Text(g.name, fontSize = 12.sp, maxLines = 1)
                        Text("${g.price} ${p.currency}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (p.history.isNotEmpty()) {
            Card("История") {
                p.history.forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.reason, fontSize = 14.sp, maxLines = 2)
                            Text(dateOf(h.ts), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            (if (h.delta > 0) "+" else "") + "${h.delta} ${p.currency}",
                            color = if (h.delta > 0) Color(0xFF22C55E) else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }

    // ---- dialogs ----
    buy?.let { (months, price, group) ->
        AlertDialog(
            onDismissRequest = { buy = null },
            title = { Text(if (group != null) "Premium для чата" else "ICQ Premium") },
            text = { Text("${monthsText(months)} за $price ${p.currency}. На балансе: ${p.balance} ${p.currency}.") },
            confirmButton = {
                TextButton(onClick = { vm.buyPremium(months, group, giftPremiumTo?.uin); buy = null; giftPremiumTo = null }) { Text("Оплатить") }
            },
            dismissButton = { TextButton(onClick = { buy = null; giftPremiumTo = null }) { Text("Отмена") } },
        )
    }
    if (pickPremiumFriend) {
        PeoplePicker("Кому подарить Premium?", contacts, includeMe = null, onDismiss = { pickPremiumFriend = false }) { u ->
            pickPremiumFriend = false
            giftPremiumTo = u
            p.plans.firstOrNull()?.let { buy = Triple(it.months, it.price, null) }
        }
    }
    if (groupPick) {
        AlertDialog(
            onDismissRequest = { groupPick = false },
            title = { Text("Premium для канала или группы") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (p.myGroups.isEmpty()) Text("Вы не администратор ни одного канала или группы")
                    p.myGroups.forEach { g ->
                        Text(
                            (if (g.kind == "channel") "📢 " else "👥 ") + g.name +
                                if (g.premiumUntil > System.currentTimeMillis()) " ✔" else "",
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                        p.groupPlans.forEach { pl ->
                            Text(
                                "${monthsText(pl.months)} — ${pl.price} ${p.currency}",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { groupPick = false; buy = Triple(pl.months, pl.price, g.id) }.padding(8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { groupPick = false }) { Text("Закрыть") } },
        )
    }
    gift?.let { g ->
        GiftDialog(g, p.currency, contacts, onDismiss = { gift = null }) { to, text, anon ->
            vm.sendGift(to, g.id, text, anon)
            gift = null
        }
    }
    if (topUp) {
        AlertDialog(
            onDismissRequest = { topUp = false },
            icon = { Icon(Icons.Filled.AccountBalanceWallet, null) },
            title = { Text("Пополнение баланса") },
            text = { Text(p.topUp) },
            confirmButton = {
                TextButton(onClick = { topUp = false; onOpenChat(if (p.supportUin != 0L) p.supportUin else com.icqnew.app.domain.BOT_UIN) }) {
                    Text("Написать в поддержку")
                }
            },
            dismissButton = { TextButton(onClick = { topUp = false }) { Text("Закрыть") } },
        )
    }
}

private fun monthsText(m: Int): String = when (m) {
    1 -> "1 месяц"
    in 2..4 -> "$m месяца"
    12 -> "1 год"
    else -> "$m месяцев"
}

@Composable
private fun GiftDialog(
    g: GiftDto, currency: String, contacts: List<User>, onDismiss: () -> Unit,
    onSend: (to: Long, text: String, anonymous: Boolean) -> Unit,
) {
    var to by remember { mutableStateOf<User?>(null) }
    var picking by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var anon by remember { mutableStateOf(false) }
    val meUin = androidx.compose.ui.platform.LocalContext.current.let { ctx: android.content.Context ->
        var u by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) { u = runCatching { ctx.appContainer().store.snapshot().uin }.getOrDefault(0L) }
        u
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiImage(g.emoji, 34.dp); Spacer(Modifier.width(10.dp))
                Column { Text(g.name); Text("${g.price} $currency", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
            }
        },
        text = {
            Column {
                Text("Кому", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { picking = true }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val u = to
                    if (u != null) {
                        Avatar(u.name, u.uin, u.avatarUrl, false, 32.dp); Spacer(Modifier.width(10.dp))
                        EmojiText(if (u.uin == meUin) "Себе" else u.name, modifier = Modifier.weight(1f))
                    } else Text("Выбрать…", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(text, { text = it.take(200) }, placeholder = { Text("Пожелание (необязательно)") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Анонимно", modifier = Modifier.weight(1f))
                    Switch(anon, { anon = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = to != null, onClick = { to?.let { onSend(it.uin, text, anon) } }) { Text("Подарить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (picking) {
        PeoplePicker("Кому подарок?", contacts, includeMe = meUin.takeIf { it != 0L }, onDismiss = { picking = false }) {
            to = it; picking = false
        }
    }
}

/** My contacts (+ "Себе" when [includeMe] is my uin). */
@Composable
private fun PeoplePicker(title: String, contacts: List<User>, includeMe: Long?, onDismiss: () -> Unit, onPick: (User) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (includeMe != null) item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(User(includeMe, "Себе", null, false, 0, "")) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Gold), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Verified, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(10.dp)); Text("Себе", fontWeight = FontWeight.SemiBold)
                    }
                }
                items(contacts.filter { !it.isGroup && it.uin != includeMe }) { u ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(u) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(u.name, u.uin, u.avatarUrl, u.online, 36.dp); Spacer(Modifier.width(10.dp))
                        EmojiText(u.name, maxLines = 1)
                    }
                }
                if (contacts.isEmpty() && includeMe == null) item { Text("Сначала добавьте контакты") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ================================================================== Account

/** «Аккаунт»: what the account is (phone, @name, e-mail, Premium, balance), sessions, log out. */
@Composable
fun AccountPage(vm: HomeViewModel, onOpen: (String) -> Unit, onLoggedOut: () -> Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    val profile by vm.myProfile.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadSessions() }
    var askLogout by remember { mutableStateOf(false) }
    val s = session ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val container = ctx.appContainer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val others by androidx.compose.runtime.produceState(emptyList<com.icqnew.app.data.local.Accounts.Saved>(), s.uin) {
        value = com.icqnew.app.data.local.Accounts.others(ctx, container.store)
    }
    var scanning by remember { mutableStateOf(false) }
    var passwordDialog by remember { mutableStateOf(false) }
    if (scanning) {
        com.icqnew.app.presentation.components.QrScannerDialog(
            title = "Сканировать QR-код",
            hint = "Наведите камеру на QR-код на другом телефоне или на icq-new.online на компьютере",
            onResult = { text ->
                scanning = false
                if (text.startsWith("icqnew-login:")) vm.approveWebLogin(text)
                else android.widget.Toast.makeText(ctx, "Это не QR-код входа ICQ New", android.widget.Toast.LENGTH_SHORT).show()
            },
            onDismiss = { scanning = false },
        )
    }
    if (passwordDialog) {
        PasswordDialog(hasPassword = profile?.hasPassword == true, onDismiss = { passwordDialog = false }) { pw, old ->
            scope.launch {
                runCatching { container.chatRepository.setPassword(pw, old) }
                    .onSuccess {
                        passwordDialog = false
                        vm.loadMyProfile(s.uin)
                        android.widget.Toast.makeText(ctx, "Пароль сохранён", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { android.widget.Toast.makeText(ctx, it.message ?: "Ошибка", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // ---- accounts on this phone ----
        Card("Аккаунты") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.name, s.uin, s.avatarUrl, false, 38.dp, storyRing = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("1 · текущий" + (s.username.takeIf { it.isNotBlank() }?.let { " · @$it" } ?: ""), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
            others.forEachIndexed { i, a ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { scope.launch { com.icqnew.app.data.local.Accounts.switchTo(ctx, container.store, a.uin) } }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(a.name, a.uin, a.avatarUrl, false, 38.dp, storyRing = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 15.sp)
                        Text("${i + 2} · нажмите, чтобы переключиться", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (others.size + 1 < com.icqnew.app.data.local.Accounts.MAX) {
                Row2(Icons.Filled.PersonAdd, "Добавить аккаунт", tint = MaterialTheme.colorScheme.primary) {
                    scope.launch { com.icqnew.app.data.local.Accounts.startAdding(ctx, container.store) }
                }
            }
            Row2(Icons.Filled.QrCodeScanner, "Сканировать QR-код", "вход на другом устройстве") { scanning = true }
        }
        Card("ICQ Premium") {
            val until = profile?.premiumUntil ?: 0L
            Row2(Icons.Filled.Verified, if (until > System.currentTimeMillis()) "Подключён" else "Не подключён",
                if (until > System.currentTimeMillis()) "до ${dateOf(until)}" else null, tint = com.icqnew.app.presentation.components.BotBlue) { onOpen("premium") }
            Row2(Icons.Filled.AccountBalanceWallet, "Баланс", "🪙 ${profile?.balance ?: 0} Gold") { onOpen("premium") }
        }
        Card("Галочки") {
            Text(
                "Синяя галочка — у пользователей ICQ Premium. Зелёная — у официальных аккаунтов и каналов: " +
                    "её выдаёт администратор после проверки.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp, 8.dp),
            )
            Row2(Icons.Filled.Verified, "Получить зелёную галочку", "написать администратору", tint = Color(0xFF22C55E)) {
                onOpen("support")
            }
        }
        Card("Активные сеансы") {
            sessions.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Devices, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.device + if (d.current) " · это устройство" else "", fontSize = 15.sp)
                        Text(if (d.online) "в сети" else "был(а) " + dateOf(d.lastUsed), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!d.current) TextButton(onClick = { vm.endSession(d.id) }) { Text("Завершить", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Card(null) {
            Row2(Icons.AutoMirrored.Filled.Logout, "Выйти из аккаунта", tint = MaterialTheme.colorScheme.error) { askLogout = true }
        }
    }
    if (askLogout) {
        AlertDialog(
            onDismissRequest = { askLogout = false },
            title = { Text("Выйти?") },
            text = { Text("Переписка останется на сервере, войти снова можно по номеру телефона.") },
            confirmButton = { TextButton(onClick = { askLogout = false; vm.logout(onLoggedOut) }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { askLogout = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PasswordDialog(hasPassword: Boolean, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var old by remember { mutableStateOf("") }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val pt = androidx.compose.ui.text.input.PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPassword) "Сменить пароль" else "Задать пароль") },
        text = {
            Column {
                if (hasPassword) OutlinedTextField(old, { old = it.take(128) }, singleLine = true, label = { Text("Текущий пароль") },
                    visualTransformation = pt, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(p1, { p1 = it.take(128) }, singleLine = true, label = { Text("Новый пароль") },
                    visualTransformation = pt, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(p2, { p2 = it.take(128) }, singleLine = true, label = { Text("Повторите пароль") },
                    visualTransformation = pt, isError = p2.isNotEmpty() && p1 != p2, modifier = Modifier.fillMaxWidth())
                Text("От 6 символов.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = p1.length >= 6 && p1 == p2 && (!hasPassword || old.isNotEmpty()),
                onClick = { onSave(p1, old.ifEmpty { null }) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ================================================================== Edit

/** «Изменить»: photo, name, surname, @username, about, e-mail, birthday, colour, my channel. */
@Composable
fun EditAccountPage(vm: HomeViewModel, onPickAvatar: () -> Unit, onDone: () -> Unit, onFragment: () -> Unit = {}) {
    val session by vm.session.collectAsStateWithLifecycle()
    val profile by vm.myProfile.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val s = session ?: return
    val p = profile
    var name by remember(p) { mutableStateOf(p?.name ?: s.name) }
    var surname by remember(p) { mutableStateOf(p?.surname.orEmpty()) }
    var username by remember(s.username) { mutableStateOf(s.username.orEmpty()) }
    var about by remember(p) { mutableStateOf(p?.about ?: s.about) }
    var email by remember(p) { mutableStateOf(p?.email.orEmpty()) }
    var birthday by remember(p) { mutableStateOf(p?.birthday.orEmpty()) }
    var color by remember(p) { mutableStateOf(p?.color ?: 0) }
    var channelId by remember(p) { mutableStateOf(p?.channelId ?: 0L) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(username) {
        val v = username.trim().removePrefix("@")
        if (v.isEmpty() || v == s.username) { hint = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(400)
        hint = vm.checkUsername(v) ?: "✔ Имя свободно"
    }
    val premium = p?.premium == true
    val premiumInfo by vm.premium.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadPremium(silent = true) }
    // only channels I created myself
    val ownedIds = premiumInfo?.myGroups.orEmpty().filter { it.owner && it.kind == "channel" }.map { -it.id }.toSet()
    val channels = chats.orEmpty().filter { it.peer.isChannel && it.peer.uin in ownedIds }.map { it.peer }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clickable(onClick = onPickAvatar)) {
                Avatar(s.name, s.uin, s.avatarUrl, false, 96.dp, storyRing = false)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.CameraAlt, "Фото", tint = Color.White, modifier = Modifier.size(17.dp)) }
            }
            TextButton(onClick = onPickAvatar) { Text("Изменить фото") }
        }
        Card("Имя") {
            Field(name, { name = it.take(40) }, "Имя")
            Field(surname, { surname = it.take(40) }, "Фамилия (необязательно)")
        }
        Card("Имя пользователя") {
            Field(username, { username = it.trim().take(32) }, "@username")
            hint?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = if (it.startsWith("✔")) Color(0xFF22C55E) else MaterialTheme.colorScheme.error) }
        }
        ExtraUsernames(onFragment)
        Card("О себе") { Field(about, { about = it.take(140) }, "Пара слов о себе") }
        PasswordSetting(vm)
        Card("Контакты") {
            Field(email, { email = it.trim().take(80) }, "Email", KeyboardType.Email)
            Text(
                "Телефон: +${s.phone} · сменить номер можно через поддержку",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 6.dp),
            )
        }
        Card("День рождения") { Field(birthday, { birthday = it.take(10) }, "ДД.ММ или ДД.ММ.ГГГГ", KeyboardType.Number) }
        Card("Цвет профиля") {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                (0..8).forEach { i ->
                    val c = profileColor(i)
                    val locked = i > 4 && !premium
                    Box(
                        Modifier.size(30.dp).clip(CircleShape)
                            .background(c ?: MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(if (color == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { if (locked) hint = "Этот цвет — с ICQ Premium" else color = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            locked -> Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            color == i -> Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (!premium) Text("🔒 Цвета 5–8 — с Premium", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        }
        if (channels.isNotEmpty()) Card("Мой канал в профиле") {
            (listOf<User?>(null) + channels).forEach { ch ->
                Row(
                    Modifier.fillMaxWidth().clickable { channelId = ch?.uin?.let { -it } ?: 0L }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ch?.name ?: "Не показывать", modifier = Modifier.weight(1f))
                    if ((ch?.uin?.let { -it } ?: 0L) == channelId) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Box(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary)
                .clickable { vm.saveAccount(name, surname, about, email, birthday, color, channelId, username, onDone) }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Сохранить", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
}

@Composable
private fun Field(v: String, on: (String) -> Unit, label: String, kb: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        v, on, placeholder = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The support chat ("Поддержка"). */
fun supportPeer(info: com.icqnew.app.data.remote.PremiumInfoDto?): Long =
    info?.supportUin?.takeIf { it != 0L } ?: com.icqnew.app.domain.BOT_UIN


/** Extra @names for the same account: they are bought on Fragment (for Gold), each one finds you. */
@Composable
private fun ExtraUsernames(onFragment: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val repo = ctx.appContainer().chatRepository
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var list by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { repo.myUsernames() }.onSuccess { list = it.first } }
    Card("Другие имена пользователя") {
        list.forEach { u ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AlternateEmail, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("@$u", fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch { runCatching { repo.removeUsername(u) }.onSuccess { list = it.first } }
                }) { Text("Убрать", color = MaterialTheme.colorScheme.error) }
            }
        }
        Row2(Icons.Filled.Diamond, "Fragment — купить @имя", "короткие и красивые имена за Gold", tint = Color(0xFF2F8CFF)) {
            onFragment()
        }
    }
}

/** Fragment: @names on sale for Gold (short ones are put up by the admin) and any other free name. */
@Composable
fun FragmentPage(vm: HomeViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val repo = ctx.appContainer().chatRepository
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session by vm.session.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf<com.icqnew.app.data.remote.FragmentDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var custom by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        runCatching { repo.fragment() }.onSuccess { info = it; error = null }.onFailure { error = it.message ?: "Нет соединения" }
    }
    LaunchedEffect(custom) {
        hint = null
        if (custom.length < 4) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        hint = vm.checkUsername(custom) ?: "✔ Имя свободно"
    }
    fun buy(name: String, lot: Boolean) {
        busy = true
        scope.launch {
            runCatching { if (lot) repo.fragmentBuy(name) else repo.addUsername(name) }
                .onSuccess {
                    android.widget.Toast.makeText(ctx, "@$name теперь ваше", android.widget.Toast.LENGTH_SHORT).show()
                    custom = ""
                    session?.uin?.let { vm.loadMyProfile(it) }
                    reload++
                }
                .onFailure { android.widget.Toast.makeText(ctx, it.message ?: "Не удалось", android.widget.Toast.LENGTH_LONG).show() }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1B2A4A), Color(0xFF2F8CFF))))
                .padding(18.dp),
        ) {
            Text("💎 Fragment", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Покупайте @имена за Gold: они найдут вас и откроют ваш профиль.", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            Text("Баланс: 🪙 ${info?.balance ?: 0} Gold", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = { reload++ }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Повторить") }
        }
        Card("В продаже") {
            val lots = info?.lots.orEmpty()
            if (info == null && error == null) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp))
            } else if (lots.isEmpty()) Text(
                "Сейчас редких имён в продаже нет. Загляните позже.", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )
            lots.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("@" + l.username, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f))
                    Text("🪙 ${l.price}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Button(enabled = !busy, onClick = { confirm = l.username to l.price }) { Text("Купить") }
                }
            }
        }
        Card("Своё имя") {
            Field(custom, { custom = it.trim().removePrefix("@").take(32) }, "@имя (от 4 символов)")
            hint?.let {
                Text(it, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    color = if (it.startsWith("✔")) Color(0xFF22C55E) else MaterialTheme.colorScheme.error)
            }
            val price = info?.customPrice ?: 50
            androidx.compose.material3.Button(
                enabled = !busy && hint?.startsWith("✔") == true,
                onClick = { confirm = "*" + custom to price },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("Купить за $price Gold") }
            Text(
                "Короткие имена (1–3 символа) продаются только в разделе «В продаже». Пополнить баланс — через Поддержку.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 2.dp, 16.dp, 10.dp),
            )
        }
    }
    confirm?.let { (raw, price) ->
        val lot = !raw.startsWith("*")
        val name = raw.removePrefix("*")
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Купить @$name?") },
            text = { Text("С баланса будет списано $price Gold. Имя станет вашим дополнительным @именем.") },
            confirmButton = { TextButton(onClick = { confirm = null; buy(name, lot) }) { Text("Купить") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}


/** «Изменить» → Пароль: then you can sign in by ID / @name / e-mail + password. */
@Composable
private fun PasswordSetting(vm: HomeViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val repo = ctx.appContainer().chatRepository
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session by vm.session.collectAsStateWithLifecycle()
    val profile by vm.myProfile.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Card("Вход по паролю") {
        Row2(Icons.Filled.Lock, "Пароль", if (profile?.hasPassword == true) "задан · изменить" else "не задан") { open = true }
        Text(
            "Войти можно по ID ${session?.uin ?: ""}, @имени или почте и паролю.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp, 2.dp, 14.dp, 8.dp),
        )
    }
    if (open) PasswordDialog(hasPassword = profile?.hasPassword == true, onDismiss = { open = false }) { pw, old ->
        scope.launch {
            runCatching { repo.setPassword(pw, old) }
                .onSuccess {
                    open = false
                    session?.uin?.let { vm.loadMyProfile(it) }
                    android.widget.Toast.makeText(ctx, "Пароль сохранён", android.widget.Toast.LENGTH_SHORT).show()
                }
                .onFailure { android.widget.Toast.makeText(ctx, it.message ?: "Ошибка", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
}
