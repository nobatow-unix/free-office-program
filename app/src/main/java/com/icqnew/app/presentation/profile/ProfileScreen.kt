package com.icqnew.app.presentation.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.foundation.shape.RoundedCornerShape
import com.icqnew.app.presentation.appContainer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.icqnew.app.domain.ChatPrefs
import com.icqnew.app.domain.ChatRepository
import com.icqnew.app.domain.User
import com.icqnew.app.presentation.appViewModel
import com.icqnew.app.presentation.components.usernameCopy
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.LocalServerUrl
import com.icqnew.app.presentation.components.formatPresence
import com.icqnew.app.presentation.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(private val uin: Long, private val repo: ChatRepository) : ViewModel() {
    private val fetched = MutableStateFlow<User?>(null)

    /** Contact from the local list if we have it, otherwise what the server returned. */
    val user: StateFlow<User?> = combine(repo.observePeer(uin), fetched) { local, remote -> local ?: remote }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val prefs: StateFlow<ChatPrefs> =
        repo.observePrefs(uin).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatPrefs())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _card = MutableStateFlow<com.icqnew.app.domain.ContactCard?>(null)
    /** Phone (if visible), my name for him, who added whom. */
    val card: StateFlow<com.icqnew.app.domain.ContactCard?> = _card.asStateFlow()
    val stories = repo.stories

    init {
        viewModelScope.launch { fetched.value = repo.fetchUser(uin) }
        loadCard()
    }

    fun loadCard() {
        viewModelScope.launch { _card.value = repo.contactCard(uin) }
    }

    fun addContact(phone: String?, query: String?, name: String?, share: Boolean, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                repo.addContactBy(phone, query, name, share)
                _message.value = "Контакт сохранён"
                loadCard()
                onResult(true)
            } catch (e: Exception) {
                _message.value = e.message ?: "Не удалось добавить"
                onResult(false)
            }
        }
    }

    fun removeContact() {
        viewModelScope.launch {
            runCatching { repo.removeContact(uin) }
            _message.value = "Удалён(а) из контактов"
            loadCard()
        }
    }

    fun consume() {
        _message.value = null
    }

    fun write(onReady: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.addContact(uin) }
            onReady()
        }
    }

    fun setMuted(v: Boolean) = viewModelScope.launch { repo.setMuted(uin, v) }
    fun setPinned(v: Boolean) = viewModelScope.launch { repo.setPinned(uin, v) }
    fun clear() = viewModelScope.launch { repo.clearHistory(uin, false); _message.value = "История очищена" }
    fun setBlocked(v: Boolean) = viewModelScope.launch {
        try {
            repo.setBlocked(uin, v)
            _message.value = if (v) "Пользователь заблокирован" else "Пользователь разблокирован"
        } catch (e: Exception) {
            _message.value = e.userMessage()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(uin: Long, onBack: () -> Unit, onOpenChat: () -> Unit, onOpenChannel: (Long) -> Unit = {}) {
    val vm = appViewModel(key = "profile$uin") { c -> ProfileViewModel(uin, c.chatRepository) }
    val user by vm.user.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var bigPhoto by remember { mutableStateOf(false) }
    val container = context.appContainer()
    var pendingVideo by remember { mutableStateOf(false) }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[android.Manifest.permission.RECORD_AUDIO] != false &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) container.callManager.startCall(uin, vm.user.value?.name ?: "ID $uin", vm.user.value?.avatarUrl, pendingVideo)
        else Toast.makeText(context, "Нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
    }
    fun call(video: Boolean) {
        pendingVideo = video
        val need = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (video) add(android.Manifest.permission.CAMERA)
        }.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) container.callManager.startCall(uin, vm.user.value?.name ?: "ID $uin", vm.user.value?.avatarUrl, video)
        else callPerms.launch(need.toTypedArray())
    }
    val card by vm.card.collectAsStateWithLifecycle()
    val storyGroups by vm.stories.collectAsStateWithLifecycle()
    val storyGroup = storyGroups.firstOrNull { it.user.uin == uin && it.stories.isNotEmpty() }
    var showStory by remember { mutableStateOf(false) }
    var editContact by remember { mutableStateOf(false) }
    if (showStory && storyGroup != null) {
        com.icqnew.app.presentation.stories.StoryViewer(
            groups = listOf(storyGroup), start = 0, myUin = -1L, onClose = { showStory = false },
        )
    }
    if (editContact) {
        com.icqnew.app.presentation.home.AddContactDialog(
            initial = card ?: user?.let { com.icqnew.app.domain.ContactCard(it, "", null, false, false, 0) },
            onDismiss = { editContact = false },
            onAdd = { phone, query, name, share, done ->
                vm.addContact(phone, query, name, share) { ok -> if (ok) editContact = false; done() }
            },
        )
    }
    var confirmBlock by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consume() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.clickable(
                    enabled = storyGroup != null || user?.avatarUrl != null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {
                    if (storyGroup != null) showStory = true else bigPhoto = true
                },
            ) {
                Avatar(user?.name ?: "?", uin, user?.avatarUrl, user?.online ?: false, 112.dp)
            }
            if (storyGroup != null) {
                Text(
                    if (storyGroup.allSeen) "История · нажмите на фото" else "Новая история · нажмите на фото",
                    fontSize = 12.sp, color = com.icqnew.app.presentation.theme.IcqGreen,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            com.icqnew.app.presentation.components.NameLine(user?.name ?: "ID $uin", verified = user?.verified == true, fontSize = 24.sp, bot = uin == com.icqnew.app.domain.BOT_UIN)
            Text(
                if (prefs.blocked) "заблокирован" else user?.let { formatPresence(it.online, it.lastSeen, it.uin) } ?: "",
                color = if (user?.online == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            // Telegram-like buttons: chat · call · video · sound
            val isBot = uin == com.icqnew.app.domain.BOT_UIN
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileButton(Icons.AutoMirrored.Filled.Chat, "Чат", Modifier.weight(1f)) { vm.write(onOpenChat) }
                if (!isBot && !prefs.blocked) {
                    ProfileButton(Icons.Filled.Call, "Звонок", Modifier.weight(1f)) { call(false) }
                    ProfileButton(Icons.Filled.Videocam, "Видео", Modifier.weight(1f)) { call(true) }
                }
                ProfileButton(
                    if (prefs.muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                    if (prefs.muted) "Без звука" else "Звук", Modifier.weight(1f),
                ) { vm.setMuted(!prefs.muted) }
            }
            Spacer(Modifier.height(12.dp))

            Card(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                // personal channel first (like Telegram); the numeric ID is only told by the bot
                card?.user?.takeIf { it.channelId != 0L }?.let { u ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenChannel(u.channelId) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        overlineContent = { Text("Канал") },
                        leadingContent = { Avatar(u.channelName, -u.channelId, u.channelAvatar, false, 44.dp, isChannel = true) },
                        headlineContent = { com.icqnew.app.presentation.emoji.EmojiText(u.channelName, maxLines = 1, fontSize = 17.sp) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                user?.username?.takeIf { it.isNotBlank() }?.let { un ->
                    val link = com.icqnew.app.presentation.IcqLinks.user(un)
                    ListItem(
                        modifier = Modifier.usernameCopy(un, link),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        overlineContent = { Text("Имя пользователя") },
                        headlineContent = { Text("@$un", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary) },
                        supportingContent = { Text("нажмите — скопировать, удерживайте — ссылка", fontSize = 12.sp) },
                        trailingContent = {
                            IconButton(onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, link)
                                context.startActivity(android.content.Intent.createChooser(send, "Поделиться"))
                            }) { Icon(Icons.Filled.Share, "Поделиться") }
                        },
                    )
                }
                val phone = card?.phone.orEmpty()
                if (phone.isNotBlank()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        overlineContent = { Text("Телефон") },
                        headlineContent = { Text(phone, fontSize = 17.sp) },
                        trailingContent = {
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("phone", phone))
                                Toast.makeText(context, "Номер скопирован", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Filled.ContentCopy, "Копировать") }
                        },
                    )
                }
                card?.user?.birthday?.takeIf { it.isNotBlank() }?.let { b ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        overlineContent = { Text("День рождения") },
                        headlineContent = { Text("🎂 $b", fontSize = 17.sp) },
                    )
                }
                card?.let { c ->
                    if (c.iAdded || c.addedMe) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            overlineContent = { Text("Контакт") },
                            headlineContent = {
                                Text(
                                    when {
                                        c.mutual -> "Вы в контактах друг у друга"
                                        c.addedMe -> "Добавил(а) вас в контакты"
                                        else -> "В ваших контактах" + (c.alias?.let { " как «$it»" } ?: "")
                                    }
                                )
                            },
                            leadingContent = { com.icqnew.app.presentation.home.ContactArrow(c) },
                        )
                    }
                }
                val about = user?.about.orEmpty()
                if (about.isNotBlank()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        overlineContent = { Text("О себе") },
                        headlineContent = { Text(about) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (uin != com.icqnew.app.domain.BOT_UIN) {
                val c = card
                Action(
                    Icons.Filled.PersonAdd,
                    when {
                        c?.iAdded == true -> "Изменить контакт"
                        c?.addedMe == true -> "Добавить в ответ"
                        else -> "Добавить в контакты"
                    },
                ) { editContact = true }
                if (c?.iAdded == true) {
                    Action(Icons.Filled.PersonRemove, "Удалить из контактов", tint = MaterialTheme.colorScheme.error) { vm.removeContact() }
                }
            }
            Action(
                if (prefs.muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                if (prefs.muted) "Включить уведомления" else "Отключить уведомления",
            ) { vm.setMuted(!prefs.muted) }
            Action(
                if (prefs.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                if (prefs.pinned) "Открепить чат" else "Закрепить чат",
            ) { vm.setPinned(!prefs.pinned) }
            Action(Icons.Filled.DeleteSweep, "Очистить историю") { vm.clear() }
            Action(
                Icons.Filled.Block,
                if (prefs.blocked) "Разблокировать" else "Заблокировать",
                tint = MaterialTheme.colorScheme.error,
            ) { if (prefs.blocked) vm.setBlocked(false) else confirmBlock = true }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text("Заблокировать ${user?.name ?: ""}?") },
            text = { Text("Пользователь не сможет писать вам и не увидит, что вы печатаете.") },
            confirmButton = {
                TextButton(onClick = { confirmBlock = false; vm.setBlocked(true) }) {
                    Text("Заблокировать", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Отмена") } },
        )
    }

    if (bigPhoto) {
        val base = LocalServerUrl.current
        val url = user?.avatarUrl?.let { if (it.startsWith("/")) base + it else it }
        Dialog(onDismissRequest = { bigPhoto = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { bigPhoto = false },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, text: String, tint: Color? = null, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(text, color = tint ?: MaterialTheme.colorScheme.onSurface) },
    )
}


@Composable
private fun ProfileButton(icon: ImageVector, text: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
