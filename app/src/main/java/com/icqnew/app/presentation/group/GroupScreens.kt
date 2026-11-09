package com.icqnew.app.presentation.group

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RemoveModerator
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.icqnew.app.data.local.SessionStore
import com.icqnew.app.domain.ChatPrefs
import com.icqnew.app.domain.ChatRepository
import com.icqnew.app.domain.GroupInfo
import com.icqnew.app.domain.PublicChat
import com.icqnew.app.domain.User
import com.icqnew.app.domain.groupPeer
import com.icqnew.app.presentation.appViewModel
import com.icqnew.app.presentation.chat.subscribers
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.EditMode
import com.icqnew.app.presentation.components.IcqIcons
import com.icqnew.app.presentation.components.ImageEditorDialog
import com.icqnew.app.presentation.components.formatClock
import com.icqnew.app.presentation.components.formatPresence
import com.icqnew.app.presentation.components.membersText
import com.icqnew.app.presentation.emoji.EmojiText
import com.icqnew.app.presentation.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ============================================================ new group / channel

class NewGroupViewModel(private val repo: ChatRepository) : ViewModel() {
    val contacts: StateFlow<List<User>> =
        repo.observeContacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun create(name: String, about: String, public: Boolean, channel: Boolean, members: List<Long>, onDone: (Long) -> Unit) {
        if (name.isBlank() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                onDone(repo.createGroup(name.trim(), members, channel, about.trim(), public))
            } catch (e: Exception) {
                _error.value = e.userMessage()
            } finally {
                _busy.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(channel: Boolean, onBack: () -> Unit, onCreated: (Long) -> Unit) {
    val vm = appViewModel(key = "new$channel") { c -> NewGroupViewModel(c.chatRepository) }
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var public by remember { mutableStateOf(channel) }
    val selected = remember { mutableStateListOf<Long>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (channel) "Новый канал" else "Новая группа")
                        Text(
                            if (selected.isEmpty()) (if (channel) "Подписчиков можно добавить позже" else "Выберите участников")
                            else "Выбрано: ${selected.size}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (name.isNotBlank()) {
                FloatingActionButton(
                    onClick = { vm.create(name, about, public, channel, selected.toList(), onCreated) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Filled.Check, "Создать", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (channel) IcqIcons.Channels else IcqIcons.Contacts, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, singleLine = true,
                    label = { Text(if (channel) "Название канала" else "Название группы") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = about, onValueChange = { about = it.take(255) }, maxLines = 3,
                label = { Text("Описание (необязательно)") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            ListItem(
                modifier = Modifier.clickable { public = !public },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Filled.Public, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(if (channel) "Публичный канал" else "Публичная группа") },
                supportingContent = { Text("Можно найти через поиск и вступить самому") },
                trailingContent = { Switch(checked = public, onCheckedChange = { public = it }) },
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            Text(
                if (channel) "Подписчики" else "Участники",
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            ContactPicker(contacts, selected, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ContactPicker(contacts: List<User>, selected: MutableList<Long>, modifier: Modifier = Modifier) {
    if (contacts.isEmpty()) {
        Text(
            "Нет контактов. Найдите друзей по ID или синхронизируйте телефонную книгу.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier) {
        items(contacts, key = { it.uin }) { u ->
            val on = u.uin in selected
            ListItem(
                modifier = Modifier.clickable { if (on) selected.remove(u.uin) else selected.add(u.uin) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Avatar(u.name, u.uin, u.avatarUrl, u.online, 44.dp) },
                headlineContent = { EmojiText(u.name, fontWeight = FontWeight.Medium, maxLines = 1) },
                supportingContent = { Text(formatPresence(u.online, u.lastSeen, u.uin)) },
                trailingContent = {
                    Checkbox(checked = on, onCheckedChange = { if (on) selected.remove(u.uin) else selected.add(u.uin) })
                },
            )
        }
    }
}

// ============================================================ join a public chat

class JoinViewModel(private val gid: Long, private val repo: ChatRepository) : ViewModel() {
    private val _chat = MutableStateFlow<PublicChat?>(null)
    val chat: StateFlow<PublicChat?> = _chat.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch {
            val c = repo.previewChat(gid)
            if (c == null) _error.value = "Чат не найден" else _chat.value = c
        }
    }

    fun join(onDone: (Long) -> Unit) {
        _busy.value = true
        viewModelScope.launch {
            try {
                onDone(repo.joinChat(gid))
            } catch (e: Exception) {
                _error.value = e.userMessage()
            } finally {
                _busy.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(gid: Long, onBack: () -> Unit, onJoined: (Long) -> Unit) {
    val vm = appViewModel(key = "join$gid") { c -> JoinViewModel(gid, c.chatRepository) }
    val chat by vm.chat.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (chat?.isChannel == true) "Канал" else "Группа") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val c = chat
            if (c == null) {
                Spacer(Modifier.height(40.dp))
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
                return@Column
            }
            Spacer(Modifier.height(24.dp))
            Avatar(c.name, groupPeer(c.id), c.avatarUrl, false, 112.dp, isGroup = true, isChannel = c.isChannel)
            Spacer(Modifier.height(16.dp))
            com.icqnew.app.presentation.components.NameLine(c.name, verified = c.verified, fontSize = 24.sp)
            c.username?.let { Text("@$it", color = MaterialTheme.colorScheme.primary) }
            Text(
                if (c.isChannel) subscribers(c.count) else membersText(c.count),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.about.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                EmojiText(c.about, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { if (c.member) onJoined(groupPeer(c.id)) else vm.join(onJoined) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    when {
                        c.member -> "Открыть"
                        c.isChannel -> "Подписаться"
                        else -> "Вступить в группу"
                    },
                    fontSize = 17.sp,
                )
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ============================================================ group / channel info

class GroupViewModel(
    private val peer: Long,
    private val repo: ChatRepository,
    store: SessionStore,
) : ViewModel() {
    private val whileUi = SharingStarted.WhileSubscribed(5_000)
    val group: StateFlow<GroupInfo?> = repo.observeGroup(peer).stateIn(viewModelScope, whileUi, null)
    val prefs: StateFlow<ChatPrefs> = repo.observePrefs(peer).stateIn(viewModelScope, whileUi, ChatPrefs())
    val contacts: StateFlow<List<User>> = repo.observeContacts().stateIn(viewModelScope, whileUi, emptyList())
    val myUin: StateFlow<Long> = store.snapshots.map { it.uin }.stateIn(viewModelScope, whileUi, 0L)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { runCatching { repo.refreshGroup(peer) } }
    }

    fun consume() {
        _message.value = null
    }

    private fun run(done: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (done != null) _message.value = done
            } catch (e: Exception) {
                _message.value = e.userMessage()
            }
        }
    }

    fun edit(name: String, about: String, public: Boolean) = run("Сохранено") {
        if (name.isNotBlank()) repo.editGroup(peer, name.trim(), about.trim(), public)
    }
    fun setAvatar(jpeg: ByteArray) = run { repo.setGroupAvatar(peer, jpeg) }
    fun add(uins: List<Long>) = run { if (uins.isNotEmpty()) repo.addMembers(peer, uins) }
    fun setMuted(v: Boolean) = run { repo.setMuted(peer, v) }
    fun moderate(uin: Long, action: String, seconds: Long = 0) = run("Готово") { repo.moderate(peer, uin, action, seconds) }
    fun saveSettings(s: com.icqnew.app.domain.GroupSettings, botName: String?) =
        run("Сохранено") { repo.saveGroupSettings(peer, s, botName) }
    fun setBotAvatar(jpeg: ByteArray) = run("Фото бота обновлено") { repo.setBotAvatar(peer, jpeg) }
    fun setUsername(g: GroupInfo, u: String) = run("Сохранено") { repo.editGroup(peer, g.name, g.about, g.isPublic, u.trim().removePrefix("@")) }
    fun leave(onDone: () -> Unit) = run {
        repo.leaveGroup(peer)
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    peer: Long,
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onLeft: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val vm = appViewModel(key = "group$peer") { c -> GroupViewModel(peer, c.chatRepository, c.store) }
    val group by vm.group.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val me by vm.myUin.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<User?>(null) }
    var editPhoto by remember { mutableStateOf<android.net.Uri?>(null) }

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consume() }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) editPhoto = uri
    }
    editPhoto?.let { uri ->
        ImageEditorDialog(
            uri = uri, mode = EditMode.AVATAR, doneLabel = "Готово",
            onCancel = { editPhoto = null },
            onDone = { bytes -> editPhoto = null; vm.setAvatar(bytes) },
        )
    }
    val g = group
    val isChannel = g?.isChannel == true
    val admin = g?.iAmAdmin == true
    val owner = g?.iAmOwner == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isChannel) "Канал" else "Группа") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (admin) {
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Изменить") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Настройки") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        Avatar(g?.name ?: "?", peer, g?.avatarUrl, false, 108.dp, isGroup = true, isChannel = isChannel)
                        if (admin) {
                            SmallFloatingActionButton(
                                onClick = { photoPicker.launch("image/*") },
                                modifier = Modifier.align(Alignment.BottomEnd).size(36.dp),
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.primary,
                            ) {
                                Icon(Icons.Filled.CameraAlt, "Фото", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    com.icqnew.app.presentation.components.NameLine(g?.name ?: "", verified = g?.verified == true, fontSize = 23.sp)
                    g?.username?.let { Text("@$it", color = MaterialTheme.colorScheme.primary) }
                    Text("ID ${g?.id ?: ""}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        (if (isChannel) subscribers(g?.count ?: 0) else membersText(g?.count ?: g?.members?.size ?: 0)) +
                            if (g?.isPublic == true) " · публичный" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!g?.about.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        EmojiText(g?.about ?: "", textAlign = TextAlign.Center)
                    }
                }
                if (g != null && (g.isPublic || !g.username.isNullOrBlank())) {
                    val link = com.icqnew.app.presentation.IcqLinks.group(g.id, g.username)
                    ListItem(
                        modifier = Modifier.clickable {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, link)
                            context.startActivity(android.content.Intent.createChooser(send, "Поделиться"))
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("Поделиться ссылкой") },
                        supportingContent = { Text(link.removePrefix("https://"), color = MaterialTheme.colorScheme.primary) },
                    )
                }
                ListItem(
                    modifier = Modifier.clickable { vm.setMuted(!prefs.muted) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(
                            if (prefs.muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications, null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text("Уведомления") },
                    trailingContent = { Switch(checked = !prefs.muted, onCheckedChange = { vm.setMuted(!it) }) },
                )
                if (admin && !isChannel) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("Команды администратора") },
                        supportingContent = {
                            Text(
                                "Ответьте на сообщение: .mute 5m · .mute 1h · .mute (навсегда) · .unmute · .ban · .kick · " +
                                    ".del · .admin · .help",
                            )
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (!isChannel || admin) {
                    ListItem(
                        modifier = Modifier.clickable { adding = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(Icons.Filled.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = {
                            Text(if (isChannel) "Добавить подписчиков" else "Добавить участников", color = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
                if (isChannel) {
                    Text(
                        if (admin) "Подписчики" else "Администраторы",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
            }
            items(g?.members ?: emptyList(), key = { it.uin }) { u ->
                ListItem(
                    modifier = Modifier.clickable { if (u.uin != me) onOpenProfile(u.uin) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Avatar(u.name, u.uin, u.avatarUrl, u.online, 44.dp, isBot = u.role == "bot") },
                    headlineContent = {
                        com.icqnew.app.presentation.components.NameLine(
                            if (u.uin == me) "${u.name} (вы)" else u.name, verified = u.verified,
                            fontWeight = FontWeight.Medium, bot = u.role == "bot",
                        )
                    },
                    supportingContent = {
                        val muted = u.isMutedNow()
                        Text(
                            when {
                                muted && u.mutedUntil < 0 -> "не может писать"
                                muted -> "не может писать до " + formatClock(u.mutedUntil)
                                else -> formatPresence(u.online, u.lastSeen, u.uin)
                            },
                            color = when {
                                muted -> MaterialTheme.colorScheme.error
                                u.online -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (u.role) {
                                "owner" -> RoleBadge("создатель")
                                "admin" -> RoleBadge("админ")
                                "bot" -> RoleBadge("бот")
                                else -> Unit
                            }
                            val canManage = admin && u.uin != me && u.role != "owner" && u.role != "bot" && (owner || u.role != "admin")
                            if (canManage) {
                                IconButton(onClick = { memberMenu = u }) {
                                    Icon(Icons.Filled.MoreVert, "Действия", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                )
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    modifier = Modifier.clickable { confirmLeave = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = {
                        Text(if (isChannel) "Отписаться от канала" else "Покинуть группу", color = MaterialTheme.colorScheme.error)
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (editing && g != null) {
        var name by remember { mutableStateOf(g.name) }
        var about by remember { mutableStateOf(g.about) }
        var public by remember { mutableStateOf(g.isPublic) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (isChannel) "Изменить канал" else "Изменить группу") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Название") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = about, onValueChange = { about = it.take(255) }, maxLines = 4, label = { Text("Описание") })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { public = !public }) {
                        Text("Публичный (виден в поиске)", Modifier.weight(1f))
                        Switch(checked = public, onCheckedChange = { public = it })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.edit(name, about, public); editing = false }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } },
        )
    }

    if (adding) {
        val existing = g?.members?.map { it.uin }?.toSet() ?: emptySet()
        val candidates = contacts.filter { it.uin !in existing }
        val chosen = remember { mutableStateListOf<Long>() }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(if (isChannel) "Добавить подписчиков" else "Добавить участников") },
            text = { Box(Modifier.heightIn(max = 420.dp)) { ContactPicker(candidates, chosen) } },
            confirmButton = {
                TextButton(enabled = chosen.isNotEmpty(), onClick = { vm.add(chosen.toList()); adding = false }) {
                    Text("Добавить")
                }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Отмена") } },
        )
    }

    memberMenu?.let { u ->
        val muted = u.isMutedNow()
        AlertDialog(
            onDismissRequest = { memberMenu = null },
            title = { EmojiText(u.name, maxLines = 1) },
            text = {
                Column {
                    if (!isChannel) {
                        if (muted) {
                            MenuLine(Icons.Filled.RecordVoiceOver, "Разрешить писать") { vm.moderate(u.uin, "unmute"); memberMenu = null }
                        } else {
                            Text("Запретить писать:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("5 мин" to 300L, "1 час" to 3600L, "1 день" to 86400L, "Навсегда" to 0L).forEach { (label, sec) ->
                                    AssistChip(
                                        onClick = { vm.moderate(u.uin, "mute", sec); memberMenu = null },
                                        label = { Text(label, fontSize = 12.sp) },
                                        leadingIcon = { Icon(Icons.Filled.VoiceOverOff, null, Modifier.size(14.dp)) },
                                    )
                                }
                            }
                        }
                    }
                    if (owner) {
                        if (u.role == "admin") {
                            MenuLine(Icons.Filled.RemoveModerator, "Снять права администратора") {
                                vm.moderate(u.uin, "unadmin"); memberMenu = null
                            }
                        } else {
                            MenuLine(Icons.Filled.AdminPanelSettings, "Назначить администратором") {
                                vm.moderate(u.uin, "admin"); memberMenu = null
                            }
                        }
                    }
                    MenuLine(Icons.Filled.PersonRemove, "Удалить из ${if (isChannel) "канала" else "группы"}") {
                        vm.moderate(u.uin, "kick"); memberMenu = null
                    }
                    MenuLine(Icons.Filled.Block, "Заблокировать", MaterialTheme.colorScheme.error) {
                        vm.moderate(u.uin, "ban"); memberMenu = null
                    }
                }
            },
            confirmButton = { TextButton(onClick = { memberMenu = null }) { Text("Закрыть") } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (isChannel) "Отписаться от канала?" else "Покинуть группу?") },
            text = { Text("Переписка будет удалена с этого телефона.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave(onLeft) }) {
                    Text(if (isChannel) "Отписаться" else "Покинуть", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RoleBadge(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun MenuLine(icon: ImageVector, text: String, tint: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, color = tint ?: MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
    }
}


// ============================================================ group / channel settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(peer: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "group$peer") { c -> GroupViewModel(peer, c.chatRepository, c.store) }
    val group by vm.group.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consume() }
    }
    val g = group ?: return
    val ch = g.isChannel
    var st by remember(g.settings) { mutableStateOf(g.settings) }
    var botName by remember(g.botName) { mutableStateOf(g.botName) }
    var username by remember(g.username) { mutableStateOf(g.username.orEmpty()) }
    var editBot by remember { mutableStateOf<android.net.Uri?>(null) }
    val botPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) editBot = uri }
    editBot?.let { uri ->
        ImageEditorDialog(uri = uri, mode = EditMode.AVATAR, doneLabel = "Готово",
            onCancel = { editBot = null }, onDone = { b -> editBot = null; vm.setBotAvatar(b) })
    }
    fun save(n: com.icqnew.app.domain.GroupSettings) {
        st = n
        vm.saveSettings(n, null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ch) "Настройки канала" else "Настройки группы") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Block("Ссылка") {
                Text(
                    "ID: ${g.id}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { v -> username = v.take(33).filter { it.isLetterOrDigit() || it == '_' } },
                        singleLine = true, prefix = { Text("@") }, label = { Text("Публичное имя") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.setUsername(g, username) }) { Text("OK") }
                }
                Text(
                    "Если задать имя, ${if (ch) "канал" else "группу"} можно найти по @${username.ifBlank { "имя" }}.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Block("Бот чата") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).clickable { botPicker.launch("image/*") }) {
                        Avatar(botName, 0, g.botAvatar, false, 56.dp, isBot = true)
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = botName, onValueChange = { botName = it.take(40) }, singleLine = true,
                        label = { Text("Имя бота") }, modifier = Modifier.weight(1f),
                        trailingIcon = { com.icqnew.app.presentation.components.VerifiedBadge(18.dp) },
                    )
                    TextButton(onClick = { vm.saveSettings(st, botName) }) { Text("OK") }
                }
                Text(
                    "Нажмите на фото, чтобы поменять картинку бота. У бота всегда зелёная галочка.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (!ch) Switch2("Приветствие новичков", "Бот здоровается с каждым новым участником", st.welcome) {
                    save(st.copy(welcome = it))
                }
            }

            Block(if (ch) "Публикации" else "Права участников") {
                if (ch) {
                    Switch2("Подписывать публикации", "Под постом видно имя администратора", st.signatures) {
                        save(st.copy(signatures = it))
                    }
                } else {
                    Switch2("Отправлять фото и файлы", "Если выключить — только текст", st.membersMedia) {
                        save(st.copy(membersMedia = it))
                    }
                    Switch2("Только админы добавляют участников", "Обычные участники не смогут приглашать", st.onlyAdminsAdd) {
                        save(st.copy(onlyAdminsAdd = it))
                    }
                }
                Switch2("Реакции", "Можно ставить ❤️ 👍 😂 на сообщения", st.reactions) { save(st.copy(reactions = it)) }
            }

            if (!ch) {
                Block("Медленный режим") {
                    Text(
                        "Как часто обычный участник может писать",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(0 to "Выкл", 10 to "10с", 30 to "30с", 60 to "1м", 300 to "5м", 900 to "15м").forEach { (v, l) ->
                            FilterChip(selected = st.slowMode == v, onClick = { save(st.copy(slowMode = v)) }, label = { Text(l) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
    )
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
        content = content,
    )
}

@Composable
private fun Switch2(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            com.icqnew.app.presentation.emoji.EmojiText(title, fontSize = 16.sp)
            Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
