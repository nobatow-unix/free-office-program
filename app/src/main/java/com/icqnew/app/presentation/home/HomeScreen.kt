package com.icqnew.app.presentation.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import com.icqnew.app.presentation.components.NameLine
import com.icqnew.app.presentation.components.TypingLabel
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Archive
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.data.local.FileUtils
import com.icqnew.app.domain.CallRecord
import com.icqnew.app.domain.CallStatus
import com.icqnew.app.domain.ChatPreview
import com.icqnew.app.domain.ConnectionState
import com.icqnew.app.domain.User
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.appViewModel
import com.icqnew.app.presentation.chat.subscribers
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.plainClick
import com.icqnew.app.presentation.components.IcqIcons
import com.icqnew.app.presentation.components.IcqLogo
import com.icqnew.app.presentation.components.formatListTime
import com.icqnew.app.presentation.components.formatPresence
import com.icqnew.app.presentation.emoji.EmojiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Tab(val title: String, val icon: ImageVector)

/** Telegram-like: while the update downloads, a ring with a down arrow fills up in the top bar. */
@Composable
private fun UpdateProgressCircle(updater: com.icqnew.app.data.update.Updater) {
    val st by updater.state.collectAsStateWithLifecycle()
    val p = st.progress
    androidx.compose.animation.AnimatedVisibility(
        visible = p != null,
        enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
    ) {
        val anim by androidx.compose.animation.core.animateFloatAsState(p ?: 1f, label = "upd")
        val bounce = androidx.compose.animation.core.rememberInfiniteTransition(label = "arrow")
        val dy by bounce.animateFloat(
            -1.5f, 1.5f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(600), androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "dy",
        )
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { anim.coerceIn(0.02f, 1f) }, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            )
            Icon(
                Icons.Filled.ArrowDownward, "Загрузка обновления", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp).offset(y = dy.dp),
            )
        }
    }
}

private val TABS = listOf(
    Tab("Личные", IcqIcons.Chats),
    Tab("Группы", Icons.Filled.Groups),
    Tab("Каналы", IcqIcons.Channels),
    Tab("Боты", IcqIcons.Bot),
    Tab("Профиль", IcqIcons.Contacts),
)

/** true while the list is scrolled towards the top (the "new chat" pencil hides when scrolling down). */
@Composable
private fun androidx.compose.foundation.lazy.LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousOffset >= firstVisibleItemScrollOffset
            }.also {
                previousIndex = firstVisibleItemIndex
                previousOffset = firstVisibleItemScrollOffset
            }
        }
    }.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (Long) -> Unit,
    onOpenProfile: (Long) -> Unit,
    onSearch: () -> Unit,
    onNewGroup: () -> Unit,
    onNewChannel: () -> Unit,
    onFindChannels: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val vm = appViewModel { c ->
        HomeViewModel(c.chatRepository, c.observeChats, c.authRepository, c.settingsRepository, c.syncPhoneBook)
    }
    val chats by vm.chats.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val calls by vm.calls.collectAsStateWithLifecycle()
    val typingPeers by vm.typingPeers.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    val mentionPeers by vm.mentionPeers.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val container = context.appContainer()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fabMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // Contacts (phone-book sync) and notifications are requested once, right after login.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.READ_CONTACTS] == true) vm.syncContacts(silent = true)
    }
    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (!granted(Manifest.permission.READ_CONTACTS)) needed.add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) vm.syncContacts(silent = true) else permissions.launch(needed.toTypedArray())
    }
    val onSync = {
        if (granted(Manifest.permission.READ_CONTACTS)) vm.syncContacts(silent = false)
        else permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS))
    }

    var editAvatar by remember { mutableStateOf<android.net.Uri?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) editAvatar = uri
    }
    editAvatar?.let { uri ->
        com.icqnew.app.presentation.components.ImageEditorDialog(
            uri = uri,
            mode = com.icqnew.app.presentation.components.EditMode.AVATAR,
            doneLabel = "Готово",
            onCancel = { editAvatar = null },
            onDone = { bytes -> editAvatar = null; vm.uploadAvatar(bytes) },
        )
    }
    LaunchedEffect(Unit) { container.updater.check() }

    // ---- stories ----
    LaunchedEffect(Unit) { vm.refreshStories() }
    var storyOpen by remember { mutableStateOf<Pair<List<com.icqnew.app.domain.StoryGroup>, Int>?>(null) }
    var storyUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var storyJpeg by remember { mutableStateOf<ByteArray?>(null) }
    val storyPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) storyUri = uri
    }
    storyUri?.let { uri ->
        com.icqnew.app.presentation.components.ImageEditorDialog(
            uri = uri,
            mode = com.icqnew.app.presentation.components.EditMode.PHOTO,
            doneLabel = "Далее",
            onCancel = { storyUri = null },
            onDone = { bytes -> storyUri = null; storyJpeg = bytes },
        )
    }
    storyJpeg?.let { bytes ->
        com.icqnew.app.presentation.stories.StoryCaptionDialog(
            onPost = { text -> storyJpeg = null; vm.postStory(bytes, text, context.cacheDir) },
            onCancel = { storyJpeg = null },
        )
    }
    storyOpen?.let { (groups, start) ->
        com.icqnew.app.presentation.stories.StoryViewer(
            groups = groups, start = start, myUin = session?.uin ?: 0L,
            onClose = { storyOpen = null; vm.refreshStories() },
        )
    }
    val me = session?.let { User(it.uin, it.name, it.avatarUrl, true, 0L, verified = it.verified) }

    var pendingCall by remember { mutableStateOf<Pair<CallRecord, Boolean>?>(null) }
    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val p = pendingCall
        pendingCall = null
        if (p != null && granted(Manifest.permission.RECORD_AUDIO)) {
            container.callManager.startCall(p.first.peer, p.first.name, p.first.avatarUrl, p.second)
        }
    }
    val app by vm.app.collectAsStateWithLifecycle()
    val archivedSet = app.archived
    val allChatsRaw = chats
    val archivedChats = allChatsRaw?.filter { it.peer.uin in archivedSet }.orEmpty()
    val allChats = allChatsRaw?.filter { it.peer.uin !in archivedSet }
    var showArchive by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showArchive) { showArchive = false }
    val personal = allChats?.filter { !it.peer.isGroup && !it.peer.isChannel && it.peer.uin != com.icqnew.app.domain.BOT_UIN }
    val groupChats = allChats?.filter { it.peer.isGroup && !it.peer.isChannel }
    val channels = allChats?.filter { it.peer.isChannel }
    val bots = allChats?.filter { it.peer.uin == com.icqnew.app.domain.BOT_UIN }
    fun unreadOf(l: List<ChatPreview>?) = l?.filter { !it.muted }?.sumOf { it.unread } ?: 0
    val listStates = remember { List(4) { androidx.compose.foundation.lazy.LazyListState() } }
    val fabVisible = if (tab < 4) listStates[tab].isScrollingUp() else false
    var profilePage by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = profilePage != null) { profilePage = null }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (tab != 4) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IcqLogo(32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    if (tab == 0) "ICQ" else TABS[tab].title,
                                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                )
                                if (connection != ConnectionState.CONNECTED) {
                                    Text(
                                        if (connection == ConnectionState.CONNECTING) "Подключение…" else "Нет соединения",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        UpdateProgressCircle(container.updater)
                        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Поиск") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            }
        },
        floatingActionButton = {
            androidx.compose.animation.AnimatedVisibility(
                visible = fabVisible && tab != 3,
                enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
            ) {
                Box {
                    FloatingActionButton(
                        onClick = {
                            when (tab) {
                                1 -> onNewGroup()
                                2 -> fabMenu = true
                                else -> fabMenu = true
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = CircleShape,
                    ) { Icon(if (tab == 1) Icons.Filled.GroupAdd else Icons.Filled.Edit, "Новый чат") }
                    DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                        if (tab == 2) {
                            DropdownMenuItem(
                                text = { Text("Новый канал") },
                                leadingIcon = { Icon(IcqIcons.Channels, null) },
                                onClick = { fabMenu = false; onNewChannel() },
                            )
                            DropdownMenuItem(
                                text = { Text("Найти каналы") },
                                leadingIcon = { Icon(Icons.Filled.Search, null) },
                                onClick = { fabMenu = false; onFindChannels() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Новый чат") },
                                leadingIcon = { Icon(Icons.Filled.PersonAdd, null) },
                                onClick = { fabMenu = false; onSearch() },
                            )
                            DropdownMenuItem(
                                text = { Text("Новая группа") },
                                leadingIcon = { Icon(Icons.Filled.GroupAdd, null) },
                                onClick = { fabMenu = false; onNewGroup() },
                            )
                            DropdownMenuItem(
                                text = { Text("Новый канал") },
                                leadingIcon = { Icon(IcqIcons.Channels, null) },
                                onClick = { fabMenu = false; onNewChannel() },
                            )
                            DropdownMenuItem(
                                text = { Text("Избранное") },
                                leadingIcon = { Icon(Icons.Filled.Bookmark, null) },
                                onClick = { fabMenu = false; vm.openSaved(onOpenChat) },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {
            UpdateBanner(container.updater)
            // classic bar, without its own dark background; the open section is lit in colour
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                TABS.forEachIndexed { i, t ->
                    val badge = when (i) {
                        0 -> unreadOf(personal)
                        1 -> unreadOf(groupChats)
                        2 -> unreadOf(channels)
                        3 -> unreadOf(bots)
                        else -> 0
                    }
                    val selected = tab == i
                    NavigationBarItem(
                        selected = selected,
                        onClick = { if (tab == i && i == 4) profilePage = null; tab = i },
                        icon = {
                            BadgedBox(badge = {
                                if (badge > 0) com.icqnew.app.presentation.components.CountBadge(badge, small = true)
                            }) {
                                if (i == 4 && session != null) {
                                    Box(
                                        Modifier.size(26.dp).clip(CircleShape)
                                            .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Avatar(session!!.name, session!!.uin, session!!.avatarUrl, false, if (selected) 22.dp else 26.dp,
                                            storyRing = false)
                                    }
                                } else Icon(t.icon, t.title, Modifier.size(24.dp))
                            }
                        },
                        label = {
                            Text(t.title, fontSize = 11.sp, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { dir * it / 12 }) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
            },
            label = "tabs",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { shownTab ->
        Box(Modifier.fillMaxSize()) {
            when (shownTab) {
                0 -> ChatsList(
                    chats = personal,
                    emptyTitle = "Пока нет чатов",
                    emptyHint = "Найдите друга по ID, номеру или @имени и напишите ему",
                    emptyButton = "Найти людей",
                    onEmpty = onSearch,
                    onOpen = onOpenChat,
                    vm = vm,
                    typing = typingPeers,
                    onAvatar = onOpenProfile,
                    listState = listStates[0],
                    swipeAction = app.swipeAction,
                    mentions = mentionPeers,
                    header = {
                        com.icqnew.app.presentation.stories.StoriesRow(
                            groups = stories, me = me,
                            onOpen = { i -> storyOpen = stories to i },
                            onAdd = { storyPicker.launch("image/*") },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        if (archivedChats.isNotEmpty()) ArchiveRow(archivedChats) { showArchive = true }
                    },
                )
                1 -> ChatsList(
                    chats = groupChats,
                    emptyTitle = "Нет групп",
                    emptyHint = "Создайте группу и пригласите друзей — в каждой группе есть свой бот-помощник.",
                    emptyButton = "Создать группу",
                    onEmpty = onNewGroup,
                    onOpen = onOpenChat,
                    vm = vm,
                    typing = typingPeers,
                    onAvatar = onOpenProfile,
                    listState = listStates[1],
                    swipeAction = app.swipeAction,
                    mentions = mentionPeers,
                )
                2 -> ChatsList(
                    chats = channels,
                    emptyTitle = "Вы ещё не подписаны на каналы",
                    emptyHint = "Каналы — это публикации для большого числа читателей. Найдите интересные или создайте свой.",
                    emptyButton = "Найти каналы",
                    onEmpty = onFindChannels,
                    onOpen = onOpenChat,
                    vm = vm,
                    onAvatar = onOpenProfile,
                    listState = listStates[2],
                    swipeAction = app.swipeAction,
                    mentions = mentionPeers,
                )
                3 -> ChatsList(
                    chats = bots,
                    emptyTitle = "Здесь будут боты",
                    emptyHint = "Официальный ICQ Бот подскажет ваш ID, @имя и многое другое.",
                    emptyButton = "Написать ICQ Боту",
                    onEmpty = { onOpenChat(com.icqnew.app.domain.BOT_UIN) },
                    onOpen = onOpenChat,
                    vm = vm,
                    onAvatar = onOpenProfile,
                    listState = listStates[3],
                    swipeAction = app.swipeAction,
                    mentions = mentionPeers,
                )
                else -> androidx.compose.animation.AnimatedContent(
                    targetState = profilePage,
                    transitionSpec = {
                        if (targetState != null) {
                            androidx.compose.animation.slideInHorizontally { it } togetherWith
                                androidx.compose.animation.fadeOut()
                        } else {
                            androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.slideOutHorizontally { it }
                        }
                    },
                    label = "profile",
                ) { page ->
                    when (page) {
                        "settings" -> SubPage("Настройки", onBack = { profilePage = null }) {
                            SettingsTab(
                                vm = vm,
                                onOpenChat = onOpenChat,
                                onPickAvatar = { avatarPicker.launch("image/*") },
                                onLoggedOut = onLoggedOut,
                                onOpenProfile = onOpenProfile,
                            )
                        }
                        "contacts" -> SubPage("Контакты", onBack = { profilePage = null }, actions = {
                            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Поиск") }
                        }) {
                            ContactsTab(vm, contacts, onOpenChat, onOpenProfile, onSearch, onSync, onNewGroup)
                        }
                        else -> MyProfileTab(
                            vm = vm,
                            stories = stories,
                            onPickAvatar = { avatarPicker.launch("image/*") },
                            onOpenStory = { i -> storyOpen = stories to i },
                            onAddStory = { storyPicker.launch("image/*") },
                            onOpen = { profilePage = it },
                            onOpenChat = onOpenChat,
                            onNewChannel = onNewChannel,
                            onFindChannels = onFindChannels,
                        )
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showArchive,
                enter = androidx.compose.animation.slideInHorizontally { it },
                exit = androidx.compose.animation.slideOutHorizontally { it },
            ) {
                SubPage("Архив", onBack = { showArchive = false }) {
                    ChatsList(
                        chats = archivedChats,
                        emptyTitle = "Архив пуст",
                        emptyHint = "Смахните чат влево (настройте в Настройки → Чаты) или удерживайте его и выберите «В архив».",
                        emptyButton = "Назад",
                        onEmpty = { showArchive = false },
                        onOpen = onOpenChat,
                        vm = vm,
                        typing = typingPeers,
                        onAvatar = onOpenProfile,
                        archivedView = true,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun ArchiveRow(chats: List<ChatPreview>, onClick: () -> Unit) {
    val unread = chats.sumOf { it.unread }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF6B7B8C)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Archive, null, tint = Color.White) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Архив", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                chats.take(3).joinToString(", ") { it.peer.name }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            )
        }
        if (unread > 0) com.icqnew.app.presentation.components.CountBadge(unread, muted = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(title, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = actions,
            windowInsets = WindowInsets.statusBars,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

// ---- Chats / channels ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsList(
    chats: List<ChatPreview>?,
    emptyTitle: String,
    emptyHint: String,
    emptyButton: String,
    onEmpty: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: HomeViewModel,
    typing: Set<Long> = emptySet(),
    onAvatar: (Long) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    swipeAction: String = "delete",
    archivedView: Boolean = false,
    mentions: Set<Long> = emptySet(),
) {
    var menuFor by remember { mutableStateOf<ChatPreview?>(null) }
    var confirmDelete by remember { mutableStateOf<ChatPreview?>(null) }
    when {
        chats == null -> Unit
        chats.isEmpty() -> Column(Modifier.fillMaxSize()) {
            header?.invoke()
            Box(Modifier.weight(1f)) { EmptyState(emptyTitle, emptyHint, emptyButton, onEmpty) }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (header != null) item(key = "header") { header() }
            items(chats, key = { it.peer.uin }) { chat ->
                // in the archive a swipe always takes the chat back out
                val action = if (archivedView) "unarchive" else swipeAction
                val state = rememberSwipeToDismissBoxState(
                    confirmValueChange = { v ->
                        if (v == SwipeToDismissBoxValue.EndToStart) {
                            when (action) {
                                "archive" -> vm.setArchived(chat.peer.uin, true)
                                "unarchive" -> vm.setArchived(chat.peer.uin, false)
                                "pin" -> vm.setPinned(chat.peer.uin, !chat.pinned)
                                "mute" -> vm.setMuted(chat.peer.uin, !chat.muted)
                                "read" -> vm.markRead(chat.peer.uin)
                                else -> confirmDelete = chat
                            }
                        }
                        false // the row snaps back; the action is applied separately
                    },
                )
                val (swipeColor, swipeText, swipeIcon) = when (action) {
                    "archive" -> Triple(Color(0xFF6B7B8C), "В архив", Icons.Filled.Archive)
                    "unarchive" -> Triple(Color(0xFF22A060), "Вернуть", Icons.Filled.Unarchive)
                    "pin" -> Triple(Color(0xFF3D8BFD), if (chat.pinned) "Открепить" else "Закрепить", Icons.Filled.PushPin)
                    "mute" -> Triple(Color(0xFFF2A43A), if (chat.muted) "Со звуком" else "Без звука", Icons.Filled.NotificationsOff)
                    "read" -> Triple(Color(0xFF22A060), "Прочитано", Icons.Filled.DoneAll)
                    else -> Triple(MaterialTheme.colorScheme.error, if (chat.peer.isGroup) "Покинуть" else "Удалить", Icons.Filled.Delete)
                }
                SwipeToDismissBox(
                    modifier = Modifier.animateItem(),
                    state = state,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(swipeColor).padding(end = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(swipeText, color = Color.White, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Icon(swipeIcon, null, tint = Color.White)
                            }
                        }
                    },
                ) {
                    ChatListItem(
                        chat, typing = chat.peer.uin in typing, mentioned = chat.peer.uin in mentions,
                        onClick = { onOpen(chat.peer.uin) }, onLongClick = { menuFor = chat },
                        onAvatar = { if (chat.peer.isSelf) onOpen(chat.peer.uin) else onAvatar(chat.peer.uin) },
                    )
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    menuFor?.let { c ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { EmojiText(c.peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    MenuLine(if (c.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (c.pinned) "Открепить" else "Закрепить") {
                        vm.setPinned(c.peer.uin, !c.pinned); menuFor = null
                    }
                    MenuLine(
                        if (c.muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                        if (c.muted) "Включить уведомления" else "Без звука",
                    ) { vm.setMuted(c.peer.uin, !c.muted); menuFor = null }
                    if (c.unread > 0) MenuLine(Icons.Filled.DoneAll, "Пометить прочитанным") { vm.markRead(c.peer.uin); menuFor = null }
                    MenuLine(
                        if (archivedView) Icons.Filled.Unarchive else Icons.Filled.Archive,
                        if (archivedView) "Вернуть из архива" else "В архив",
                    ) { vm.setArchived(c.peer.uin, !archivedView); menuFor = null }
                    MenuLine(
                        Icons.Filled.Delete,
                        if (c.peer.isChannel) "Отписаться" else if (c.peer.isGroup) "Покинуть группу" else "Удалить чат",
                        MaterialTheme.colorScheme.error,
                    ) { confirmDelete = c; menuFor = null }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Закрыть") } },
        )
    }
    confirmDelete?.let { c ->
        var forAll by remember(c.peer.uin) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = {
                Text(
                    when {
                        c.peer.isChannel -> "Отписаться от канала?"
                        c.peer.isGroup -> "Покинуть группу?"
                        else -> "Удалить чат?"
                    }
                )
            },
            text = {
                Column {
                    Text(
                        if (forAll) "Переписка с «${c.peer.name}» будет удалена у вас обоих."
                        else "Переписка с «${c.peer.name}» будет удалена с этого телефона."
                    )
                    if (!c.peer.isGroup && !c.peer.isSelf) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { forAll = !forAll },
                        ) {
                            Checkbox(checked = forAll, onCheckedChange = { forAll = it })
                            Text("Также удалить у ${c.peer.name}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteChat(c.peer.uin, forAll); confirmDelete = null }) {
                    Text(if (c.peer.isGroup) "Покинуть" else "Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListItem(
    chat: ChatPreview,
    typing: Boolean,
    mentioned: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAvatar: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (chat.pinned) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onAvatar,
            ),
        ) {
            Avatar(
                chat.peer.name, chat.peer.uin, chat.peer.avatarUrl, !chat.peer.isGroup && chat.peer.online, 56.dp,
                isGroup = chat.peer.isGroup, isChannel = chat.peer.isChannel, isSelf = chat.peer.isSelf,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NameLine(
                    chat.peer.name, verified = chat.peer.verified, fontSize = 17.sp,
                    modifier = Modifier.weight(1f, fill = false), bot = chat.peer.uin == com.icqnew.app.domain.BOT_UIN,
                )
                if (chat.muted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.NotificationsOff, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            if (typing && !chat.peer.isChannel) {
                TypingLabel("печатает", MaterialTheme.colorScheme.primary, 15.sp)
            } else {
                val prefix = when {
                    chat.lastFromMe && !chat.peer.isChannel && !chat.peer.isSelf -> "Вы: "
                    chat.lastKind == com.icqnew.app.domain.MessageKind.BOT -> "🤖 "
                    chat.lastSender != null -> chat.lastSender.substringBefore(' ') + ": "
                    else -> ""
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.lastFromMe && !chat.peer.isChannel && !chat.peer.isSelf) {
                        Icon(
                            when (chat.lastStatus) {
                                com.icqnew.app.domain.MessageStatus.READ -> Icons.Filled.DoneAll
                                com.icqnew.app.domain.MessageStatus.SENDING -> Icons.Filled.Schedule
                                com.icqnew.app.domain.MessageStatus.FAILED -> Icons.Filled.ErrorOutline
                                else -> Icons.Filled.Done
                            },
                            null, Modifier.size(16.dp),
                            tint = when (chat.lastStatus) {
                                com.icqnew.app.domain.MessageStatus.READ -> MaterialTheme.colorScheme.primary
                                com.icqnew.app.domain.MessageStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    // the preview keeps bold / italic / spoiler … exactly like the message itself
                    com.icqnew.app.presentation.emoji.RichText(
                        prefix + chat.lastText.replace('\n', ' '),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp,
                        linkColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        spoilerColor = MaterialTheme.colorScheme.background,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, interactive = false,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (chat.lastTs > 0) formatListTime(chat.lastTs) else "", fontSize = 12.sp,
                color = if (chat.unread > 0 && !chat.muted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
            if (mentioned) {
                // somebody tagged me here
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("@", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(4.dp))
            }
            if (chat.unread > 0) {
                com.icqnew.app.presentation.components.CountBadge(chat.unread, muted = chat.muted)
            } else if (chat.pinned) {
                Icon(Icons.Filled.PushPin, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!mentioned) {
                Spacer(Modifier.height(20.dp))
            }
            }
        }
    }
}

// ---- Calls -----------------------------------------------------------------------

@Composable
private fun CallsTab(calls: List<CallRecord>, onProfile: (Long) -> Unit, onCall: (CallRecord, Boolean) -> Unit) {
    if (calls.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(IcqIcons.Calls, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(16.dp))
            Text("Звонков пока нет", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Позвоните другу из чата — кнопки аудио- и видеозвонка вверху экрана переписки.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(calls, key = { it.id }) { c ->
            val missed = c.status == CallStatus.MISSED
            ListItem(
                modifier = Modifier.clickable { onProfile(c.peer) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Avatar(c.name, c.peer, c.avatarUrl, false, 48.dp) },
                headlineContent = {
                    EmojiText(
                        c.name, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when {
                                missed -> Icons.AutoMirrored.Filled.CallMissed
                                c.outgoing -> Icons.AutoMirrored.Filled.CallMade
                                else -> Icons.AutoMirrored.Filled.CallReceived
                            },
                            null, Modifier.size(16.dp),
                            tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        val what = when (c.status) {
                            CallStatus.MISSED -> "Пропущенный"
                            CallStatus.DECLINED -> "Отклонён"
                            CallStatus.CANCELLED -> if (c.outgoing) "Отменён" else "Пропущенный"
                            CallStatus.DONE -> if (c.outgoing) "Исходящий" else "Входящий"
                        }
                        val dur = if (c.durationSec > 0) " · %d:%02d".format(c.durationSec / 60, c.durationSec % 60) else ""
                        Text("$what${if (c.video) " видео" else ""} · ${formatListTime(c.ts)}$dur", fontSize = 13.sp)
                    }
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onCall(c, true) }) {
                            Icon(IcqIcons.Video, "Видеозвонок", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onCall(c, false) }) {
                            Icon(IcqIcons.Calls, "Позвонить", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
        },
        headlineContent = { Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) },
    )
}

@Composable
fun EmptyState(title: String, hint: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IcqLogo(84.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClick) { Text(button) }
    }
}

@Suppress("unused")
private fun subscribersText(n: Int) = subscribers(n)


/** "New version available" strip above the bottom menu: download + install right from here. */
@Composable
private fun UpdateBanner(updater: com.icqnew.app.data.update.Updater) {
    val st by updater.state.collectAsStateWithLifecycle()
    val v = st.available
    androidx.compose.animation.AnimatedVisibility(
        visible = v != null && !st.dismissed,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        Surface(color = MaterialTheme.colorScheme.primary, contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().clickable { updater.downloadAndInstall() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SystemUpdate, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Доступна новая версия " + (v?.name ?: ""), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        )
                        Text(
                            st.error ?: when (val p = st.progress) {
                                null -> v?.notes?.takeIf { it.isNotBlank() } ?: "Нажмите, чтобы скачать и установить"
                                else -> "Загрузка… ${(p * 100).toInt()}%"
                            },
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                    if (st.progress == null) {
                        TextButton(onClick = { updater.downloadAndInstall() }) {
                            Text("Обновить", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { updater.dismiss() }) { Icon(Icons.Filled.Close, "Позже", tint = Color.White) }
                    }
                }
                st.progress?.let { p ->
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { p }, modifier = Modifier.fillMaxWidth(),
                        color = Color.White, trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}
