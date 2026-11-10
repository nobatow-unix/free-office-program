package com.icqnew.app.presentation.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.domain.StoryGroup
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.components.usernameCopy
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.IcqIcons
import com.icqnew.app.presentation.components.NameLine
import com.icqnew.app.presentation.emoji.EmojiText

/** Telegram-like profile colours (index 1..8; 0 = none). */
val PROFILE_COLORS = listOf(
    Color(0xFFE17076), Color(0xFFFAA774), Color(0xFFA695E7), Color(0xFF7BC862),
    Color(0xFF6EC9CB), Color(0xFF65AADD), Color(0xFFEE7AAE), Color(0xFF22C55E),
)

fun profileColor(i: Int): Color? = PROFILE_COLORS.getOrNull(i - 1)

/** My own profile: photo, name, @username, ID, birthday, my channel, stories, and the menu (contacts, settings…). */
@Composable
fun MyProfileTab(
    vm: HomeViewModel,
    stories: List<StoryGroup>,
    onPickAvatar: () -> Unit,
    onOpenStory: (Int) -> Unit,
    onAddStory: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenChat: (Long) -> Unit,
    onNewChannel: () -> Unit,
    onFindChannels: () -> Unit,
) {
    val context = LocalContext.current
    val session by vm.session.collectAsStateWithLifecycle()
    val profile by vm.myProfile.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val s = session ?: return
    LaunchedEffect(s.uin) { vm.loadMyProfile(s.uin); vm.loadPremium() }
    var editing by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { serverUrl = runCatching { context.appContainer().store.snapshot().serverUrl }.getOrDefault("") }
    val myIndex = stories.indexOfFirst { it.user.uin == s.uin && it.stories.isNotEmpty() }
    val color = profileColor(profile?.color ?: 0)
    val headerTint by animateColorAsState(color ?: MaterialTheme.colorScheme.primary, label = "pc")

    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        com.icqnew.app.presentation.components.QrScannerDialog(
            title = "Вход в веб-версию",
            hint = "Откройте icq-new.online на компьютере и наведите камеру на QR-код",
            onResult = { text ->
                scanning = false
                if (text.startsWith("icqnew-login:")) vm.approveWebLogin(text)
                else Toast.makeText(context, "Это не QR-код ICQ New Web", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { scanning = false },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---- header with the profile colour ----
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(headerTint.copy(alpha = 0.35f), Color.Transparent)))
                .statusBarsPadding()
                .padding(top = 18.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(100.dp).clip(CircleShape)
                        .then(if (myIndex >= 0) Modifier.border(3.dp, com.icqnew.app.presentation.theme.IcqGreen, CircleShape) else Modifier)
                        .clickable { if (myIndex >= 0) onOpenStory(myIndex) else onPickAvatar() }
                        .padding(if (myIndex >= 0) 6.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) { Avatar(s.name, s.uin, s.avatarUrl, false, 90.dp, storyRing = false) }
                Spacer(Modifier.height(8.dp))
                NameLine(
                    listOfNotNull(s.name, profile?.surname?.ifBlank { null }).joinToString(" "),
                    verified = s.verified, fontSize = 22.sp, premium = profile?.premium == true,
                )
                Text("в сети", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
            // settings: top right
            IconButton(
                onClick = { onOpen("settings") },
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp),
            ) { Icon(Icons.Filled.Settings, "Настройки") }
        }

        // ---- three buttons like Telegram ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigButton(Icons.Filled.Verified, "Premium", Modifier.weight(1f)) { onOpen("premium") }
            BigButton(Icons.Filled.Person, "Аккаунт", Modifier.weight(1f)) { onOpen("account") }
            BigButton(Icons.Filled.Edit, "Изменить", Modifier.weight(1f)) { onOpen("edit") }
        }

        // ---- info card ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            profile?.takeIf { it.channelId != 0L }?.let { p ->
                ListItem(
                    modifier = Modifier.clickable { onOpenChat(-p.channelId) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Avatar(p.channelName, -p.channelId, p.channelAvatar, false, 44.dp, isChannel = true) },
                    headlineContent = { EmojiText(p.channelName, maxLines = 1, fontSize = 17.sp) },
                    supportingContent = { Text("Мой канал", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            s.username?.takeIf { it.isNotBlank() }?.let {
                Box(Modifier.usernameCopy(it, com.icqnew.app.presentation.IcqLinks.user(it))) {
                    InfoRow("@$it", "Имя пользователя · удерживайте — ссылка")
                }
            }
            s.about.takeIf { it.isNotBlank() }?.let { InfoRow(it, "О себе") }
            // gifts I received
            val gifts by vm.gifts.collectAsStateWithLifecycle()
            LaunchedEffect(s.uin) { vm.loadGifts(s.uin) }
            if (gifts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen("premium") }.padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            gifts.take(8).forEach { g -> com.icqnew.app.presentation.emoji.EmojiImage(g.gift.emoji, 26.dp) }
                        }
                        Text("Подарки: ${gifts.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            profile?.birthday?.takeIf { it.isNotBlank() }?.let { InfoRow("🎂 " + it, "День рождения") }
        }

        // ---- my stories ----
        Text(
            "Мои публикации", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp,
            modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Box(
                    Modifier.size(width = 92.dp, height = 140.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onAddStory),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Text("Добавить", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (myIndex >= 0) {
                itemsIndexed(stories[myIndex].stories) { _, st ->
                    Box(
                        Modifier.size(width = 92.dp, height = 140.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onOpenStory(myIndex) },
                    ) {
                        StoryThumb(st.attId, st.id)
                        Text(
                            "👁 ${st.views}", color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                        )
                    }
                }
            }
        }

        // ---- menu ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            MenuRow(IcqIcons.Contacts, "Контакты", Color(0xFF65AADD)) { onOpen("contacts") }
            MenuRow(Icons.Filled.Bookmark, "Избранное", Color(0xFF22C55E)) { vm.openSaved(onOpenChat) }
            MenuRow(Icons.Filled.QrCodeScanner, "Веб-версия (вход по QR)", Color(0xFFA695E7)) {
                scanning = true
            }
            MenuRow(Icons.Filled.Share, "Пригласить друзей", Color(0xFFEE7AAE)) {
                val server = serverUrl
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(
                        Intent.EXTRA_TEXT,
                        "Пиши мне в ICQ New! " +
                            (s.username?.takeIf { it.isNotBlank() }?.let { com.icqnew.app.presentation.IcqLinks.user(it) } ?: "Найди меня в ICQ New: ${s.name}") +
                            "\nСкачать: ${com.icqnew.app.presentation.IcqLinks.SITE}",
                    )
                context.startActivity(Intent.createChooser(send, "Пригласить"))
            }
            MenuRow(Icons.Filled.SupportAgent, "Поддержка", Color(0xFF2F8CFF)) { onOpen("support") }
        }
        Spacer(Modifier.height(90.dp))
    }

    if (editing) {
        val channels = chats.orEmpty().filter { it.peer.isChannel }.map { it.peer }
        EditProfileDialog(
            name = s.name, about = s.about, birthday = profile?.birthday.orEmpty(), color = profile?.color ?: 0,
            channelId = profile?.channelId ?: 0L, channels = channels,
            onDismiss = { editing = false },
            onSave = { n, a, b, c, ch -> vm.saveProfile(n, a, b, c, ch); editing = false },
        )
    }
}

@Composable
private fun StoryThumb(attId: String, id: Long) {
    val container = LocalContext.current.appContainer()
    var file by remember(id) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(id) { file = runCatching { container.mediaCache.file(attId, "story_$id.jpg") }.getOrNull() }
    file?.let {
        coil.compose.AsyncImage(
            model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun copy(context: Context, text: String, toast: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("icq", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun BigButton(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(2.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun InfoRow(value: String, label: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            EmojiText(value, fontSize = 15.sp)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.18f), tint))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun EditProfileDialog(
    name: String,
    about: String,
    birthday: String,
    color: Int,
    channelId: Long,
    channels: List<com.icqnew.app.domain.User>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, Long) -> Unit,
) {
    var n by remember { mutableStateOf(name) }
    var a by remember { mutableStateOf(about) }
    var b by remember { mutableStateOf(birthday) }
    var c by remember { mutableIntStateOf(color) }
    var ch by remember { mutableLongStateOf(channelId) }
    var chMenu by remember { mutableStateOf(false) }
    val bOk = b.isBlank() || Regex("""\d{2}\.\d{2}(\.\d{4})?""").matches(b)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить профиль") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(n, { n = it.take(40) }, singleLine = true, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    a, { a = it.take(140) }, maxLines = 3, label = { Text("О себе") },
                    supportingText = { Text("${a.length}/140") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    b, { v -> b = v.filter { it.isDigit() || it == '.' }.take(10) }, singleLine = true,
                    label = { Text("День рождения") }, placeholder = { Text("ДД.ММ или ДД.ММ.ГГГГ") },
                    leadingIcon = { Icon(Icons.Filled.Cake, null) }, isError = !bOk,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("Цвет профиля", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { c = 0 },
                        contentAlignment = Alignment.Center,
                    ) { if (c == 0) Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                    PROFILE_COLORS.forEachIndexed { i, col ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(col).clickable { c = i + 1 },
                            contentAlignment = Alignment.Center,
                        ) { if (c == i + 1) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Мой канал в профиле", fontWeight = FontWeight.Medium)
                Box {
                    TextButton(onClick = { chMenu = true }) {
                        Text(channels.firstOrNull { -it.uin == ch || it.uin == -ch }?.name ?: if (ch != 0L) "Канал #$ch" else "Не показывать")
                    }
                    DropdownMenu(chMenu, { chMenu = false }) {
                        DropdownMenuItem(text = { Text("Не показывать") }, onClick = { ch = 0; chMenu = false })
                        channels.forEach { g ->
                            DropdownMenuItem(text = { EmojiText(g.name) }, onClick = { ch = -g.uin; chMenu = false })
                        }
                    }
                }
                if (channels.isEmpty()) {
                    Text("Создайте канал, чтобы показать его в профиле.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = n.isNotBlank() && bOk, onClick = { onSave(n, a, b, c, ch) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
