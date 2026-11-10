package com.icqnew.app.presentation.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Timer
import com.icqnew.app.domain.User
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.data.local.FileUtils
import com.icqnew.app.domain.ChatMessage
import com.icqnew.app.domain.MessageKind
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.appViewModel
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.IcqIcons
import com.icqnew.app.presentation.components.formatPresence
import com.icqnew.app.presentation.components.membersText
import com.icqnew.app.presentation.components.sameDay
import com.icqnew.app.presentation.emoji.EmojiPanel
import com.icqnew.app.presentation.emoji.EmojiText
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloat
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/** Items of the (reversed) message list: messages and day headers. */
private sealed interface ListRow {
    val key: String

    data class Msg(val m: ChatMessage, val firstOfGroup: Boolean, val lastOfGroup: Boolean) : ListRow {
        override val key get() = "m" + m.id
    }

    data class Day(val ts: Long) : ListRow {
        override val key get() = "d$ts"
    }

    data class Call(val c: com.icqnew.app.domain.CallRecord) : ListRow {
        override val key get() = "c" + c.id
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uin: Long,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenLink: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.appContainer()
    val vm = appViewModel(key = "chat$uin") { c ->
        ChatViewModel(
            uin, c.chatRepository, c.observeMessages, c.sendMessage,
            c.mediaCache, c.voicePlayer, c.newVoiceRecorder(), c.store,
        )
    }
    val peer by vm.peer.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val typing by vm.typing.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val recLevels by vm.levels.collectAsStateWithLifecycle()
    val replyTo by vm.replyTo.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val forwardTargets by vm.forwardTargets.collectAsStateWithLifecycle()

    var viewer by remember { mutableStateOf<Pair<File, ChatMessage>?>(null) }
    var fileViewer by remember { mutableStateOf<ChatMessage?>(null) }
    var creatingPoll by remember { mutableStateOf(false) }
    var attachSheet by remember { mutableStateOf(false) }
    var pendingFiles by remember { mutableStateOf<List<PendingFile>>(emptyList()) }
    val storyLoader: suspend (String) -> File? = remember(vm) { { att -> vm.storyFile(att) } }
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var forwarding by remember { mutableStateOf<ChatMessage?>(null) }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    var failedFor by remember { mutableStateOf<ChatMessage?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pickWallpaper by remember { mutableStateOf(false) }
    var pickTtl by remember { mutableStateOf(false) }
    val ttl by vm.ttl.collectAsStateWithLifecycle()
    LaunchedEffect(uin) { vm.loadTtl() }
    val appSettings by container.settingsRepository.app.collectAsStateWithLifecycle(com.icqnew.app.domain.AppSettings())
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var phoneCheck by remember { mutableStateOf<String?>(null) }
    var highlighted by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(highlighted) { if (highlighted != null) { delay(1600); highlighted = null } }
    fun onLinkTap(tag: String, value: String) {
        when (tag) {
            com.icqnew.app.presentation.emoji.RichFormat.TAG_URL -> {
                val url = if (value.startsWith("http", ignoreCase = true)) value else "https://$value"
                if (com.icqnew.app.presentation.IcqLinks.parse(url) != null) {
                    onOpenLink(url) // a person / channel inside ICQ: open it here, not in the browser
                    return
                }
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            com.icqnew.app.presentation.emoji.RichFormat.TAG_PHONE -> phoneCheck = value
            com.icqnew.app.presentation.emoji.RichFormat.TAG_MENTION -> scope.launch {
                val peerKey = vm.resolveMention(value)
                if (peerKey == null) Toast.makeText(context, "@$value не найден", Toast.LENGTH_SHORT).show()
                else if (peerKey < 0) onOpenChat(peerKey) else onOpenProfile(peerKey)
            }
        }
    }

    // ---- keyboard <-> emoji panel (only one of them is open at a time) ----
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    var showPanel by rememberSaveable { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    var keyboardPx by rememberSaveable { mutableIntStateOf(0) }
    val imeVisible = imeBottom > navBottom + 50
    LaunchedEffect(imeBottom) {
        val h = imeBottom - navBottom
        if (h > keyboardPx) keyboardPx = h
    }
    LaunchedEffect(imeVisible) { if (imeVisible) showPanel = false }
    val panelHeight = with(density) { if (keyboardPx > 200) keyboardPx.toDp() else 290.dp }
    BackHandler(enabled = showPanel) { showPanel = false }

    fun toggleEmoji() {
        if (showPanel) {
            showPanel = false
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            scope.launch {
                delay(80) // let the keyboard start hiding first, so the panel takes its place
                showPanel = true
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onEnter() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onLeave() }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    val isGroup = vm.isGroup
    val isChannel = group?.isChannel == true || peer?.isChannel == true
    val iAmAdmin = group?.iAmAdmin == true
    val mutedUntil = group?.myMutedUntil ?: 0L
    val isService = uin == com.icqnew.app.domain.SERVICE_UIN
    val canWrite = !(isChannel && !iAmAdmin) && !prefs.blocked && !isService
    val botUser = com.icqnew.app.domain.User(
        0, group?.botName ?: "ICQ Бот", group?.botAvatar, false, 0, verified = true,
    )
    // Short notices (command results) are shown as a toast.
    LaunchedEffect(info) {
        info?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeInfo()
        }
    }
    val iAmMuted = mutedUntil < 0 || mutedUntil > System.currentTimeMillis()

    // Build the reversed list with day headers (reverseLayout: index 0 = newest, at the bottom).
    val callsHere by vm.calls.collectAsStateWithLifecycle()
    val rows: List<ListRow> = remember(messages, callsHere, isChannel) {
        // messages and calls on one timeline (newest first: reverseLayout);
        // deleted channel posts are not shown at all
        val visible = if (isChannel) messages.filter { !it.deleted } else messages
        val timeline: List<Any> = (visible + callsHere).sortedBy { if (it is ChatMessage) it.ts else (it as com.icqnew.app.domain.CallRecord).ts }
        val out = ArrayList<ListRow>(timeline.size + 8)
        val rev = timeline.asReversed()
        fun tsOf(x: Any?): Long? = when (x) {
            is ChatMessage -> x.ts
            is com.icqnew.app.domain.CallRecord -> x.ts
            else -> null
        }
        for (i in rev.indices) {
            val x = rev[i]
            val older = rev.getOrNull(i + 1)
            val newer = rev.getOrNull(i - 1)
            if (x is ChatMessage) {
                val m = x
                val o = older as? ChatMessage
                val n = newer as? ChatMessage
                val first = o == null || o.fromUin != m.fromUin || o.kind == MessageKind.SYSTEM || !sameDay(o.ts, m.ts)
                val last = n == null || n.fromUin != m.fromUin || n.kind == MessageKind.SYSTEM || !sameDay(n.ts, m.ts)
                out.add(ListRow.Msg(m, first, last))
            } else if (x is com.icqnew.app.domain.CallRecord) {
                out.add(ListRow.Call(x))
            }
            val t = tsOf(x)!!
            val ot = tsOf(older)
            if (ot == null || !sameDay(ot, t)) out.add(ListRow.Day(t))
        }
        out
    }
    val byServerId: Map<Long, ChatMessage> = remember(messages) {
        messages.filter { it.serverId != null }.associateBy { it.serverId!! }
    }
    val indexOfServerId: Map<Long, Int> = remember(rows) {
        val map = HashMap<Long, Int>()
        rows.forEachIndexed { i, r -> if (r is ListRow.Msg) r.m.serverId?.let { map[it] = i } }
        map
    }

    // channel posts on screen count as "seen" (👁 counter)
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(isChannel, listState) {
        if (!isChannel) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { (currentRows.getOrNull(it.index) as? ListRow.Msg)?.m?.serverId }
        }.collectLatest { ids ->
            delay(700) // only what stays on screen for a moment
            vm.reportViews(ids)
        }
    }

    val newestId = messages.lastOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && (listState.firstVisibleItemIndex <= 3 || messages.last().fromMe)) {
            listState.animateScrollToItem(0)
        }
    }

    fun copy(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ICQ", text))
        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    // ---- pickers ----
    fun sendUri(uri: Uri) {
        // big files (up to 4 GB) are copied off the main thread
        scope.launch {
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FileUtils.copyToOutbox(context, uri) }
            if (p == null) Toast.makeText(context, "Файл слишком большой (максимум 4 ГБ)", Toast.LENGTH_LONG).show()
            else pendingFiles = pendingFiles + PendingFile(p.file, p.name, p.mime) // caption sheet opens
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendUri(uri)
    }
    // Photos go through the editor (crop / rotate / filters) before sending.
    var editPhoto by remember { mutableStateOf<Uri?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("image/") && !mime.contains("gif")) editPhoto = uri else sendUri(uri)
        }
    }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.length() > 0) editPhoto = Uri.fromFile(f) else f?.delete()
    }
    editPhoto?.let { uri ->
        com.icqnew.app.presentation.components.ImageEditorDialog(
            uri = uri,
            mode = com.icqnew.app.presentation.components.EditMode.PHOTO,
            doneLabel = "Отправить",
            onCancel = {
                editPhoto = null
                cameraFile?.delete()
                cameraFile = null
            },
            onDone = { bytes ->
                editPhoto = null
                cameraFile?.delete()
                cameraFile = null
                val out = FileUtils.newCameraFile(context)
                out.writeBytes(bytes)
                pendingFiles = pendingFiles + PendingFile(out, "photo_" + System.currentTimeMillis() + ".jpg", "image/jpeg")
            },
        )
    }
    // the app's own camera (photo → editor, video → send sheet)
    var showCamera by remember { mutableStateOf(false) }
    if (showCamera) {
        InAppCamera(
            onDismiss = { showCamera = false },
            onPhoto = { f -> showCamera = false; cameraFile = f; editPhoto = Uri.fromFile(f) },
            onVideo = { f ->
                showCamera = false
                pendingFiles = pendingFiles + PendingFile(f, "video_" + System.currentTimeMillis() + ".mp4", "video/mp4")
            },
        )
    }
    // a photo already in the send sheet can be edited again (draw, crop, size)
    var reEdit by remember { mutableStateOf<PendingFile?>(null) }
    reEdit?.let { pf ->
        com.icqnew.app.presentation.components.ImageEditorDialog(
            uri = Uri.fromFile(pf.file),
            mode = com.icqnew.app.presentation.components.EditMode.PHOTO,
            doneLabel = "Готово",
            onCancel = { reEdit = null },
            onDone = { bytes ->
                reEdit = null
                val out = FileUtils.newCameraFile(context)
                out.writeBytes(bytes)
                val np = PendingFile(out, pf.name.substringBeforeLast('.') + ".jpg", "image/jpeg")
                pendingFiles = pendingFiles.map { if (it == pf) np else it }
                pf.file.delete()
            },
        )
    }
    fun openCamera() {
        showCamera = true
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else Toast.makeText(context, "Нужен доступ к камере", Toast.LENGTH_SHORT).show()
    }
    // ---- round video messages (front camera, up to 60 s) ----
    var videoNoteFile by remember { mutableStateOf<File?>(null) }
    val videoNoteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val f = videoNoteFile
        videoNoteFile = null
        if (res.resultCode == android.app.Activity.RESULT_OK && f != null && f.length() > 0) {
            val dur = runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    r.release()
                }
            }.getOrDefault(0L)
            vm.sendVideoNote(f, dur)
        } else f?.delete()
    }
    // round video messages are recorded inside the app (CameraX): flip camera, flash
    var recordingNote by remember { mutableStateOf(false) }
    fun openVideoNote() {
        recordingNote = true
    }
    val videoNotePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true) openVideoNote()
        else Toast.makeText(context, "Нужен доступ к камере", Toast.LENGTH_SHORT).show()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(context, "Нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
    }
    var pendingVideoCall by remember { mutableStateOf(false) }
    fun startCall(video: Boolean) {
        container.callManager.startCall(uin, peer?.name ?: "ID $uin", peer?.avatarUrl, video)
    }
    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val audioOk = res[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioOk) startCall(pendingVideoCall)
        else Toast.makeText(context, "Для звонка нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
    }
    fun call(video: Boolean) {
        pendingVideoCall = video
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (video && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) startCall(video) else callPermissions.launch(needed.toTypedArray())
    }

    run {
        val host = LocalContext.current.appContainer()
        DisposableEffect(Unit) {
            host.homeOnScreen.value += 1
            onDispose { host.homeOnScreen.value -= 1 }
        }
    }
    val title = peer?.name ?: group?.name ?: if (isGroup) "Группа" else "ID $uin"
    val typingNames = typing.map { t -> members[t]?.name?.substringBefore(' ') ?: "Кто-то" }
    // recording a voice / round video instead of typing (like Telegram)
    val doing = typing.firstOrNull()?.let { vm.typingAction(it) }.orEmpty()
    val doingText = when (doing) {
        "voice" -> "записывает голосовое"
        "video" -> "записывает видео"
        else -> "печатает"
    }
    val typingText = when (typingNames.size) {
        0 -> ""
        1 -> "${typingNames[0]} $doingText"
        2 -> "${typingNames[0]} и ${typingNames[1]} печатают"
        else -> "${typingNames[0]}, ${typingNames[1]} и ещё ${typingNames.size - 2} печатают"
    }
    val wallpaper = prefs.wallpaper.ifEmpty { appSettings.wallpaper }
    val isSaved = peer?.isSelf == true
    val isBotChat = uin == com.icqnew.app.domain.BOT_UIN
    val subtitle = when {
        isSaved -> "ваши заметки и файлы"
        isBotChat && typing.isEmpty() -> "бот"
        typing.isNotEmpty() && isGroup && !isChannel -> typingText
        typing.isNotEmpty() && !isGroup -> doingText
        isChannel -> "канал · " + subscribers(group?.count ?: peer?.membersCount ?: 0)
        isGroup -> membersText(group?.count?.takeIf { it > 0 } ?: group?.members?.size ?: peer?.membersCount ?: 0)
        prefs.blocked -> "заблокирован"
        peer != null -> formatPresence(peer!!.online, peer!!.lastSeen, peer!!.uin)
        else -> ""
    }
    val highlight = typing.isNotEmpty() || (!isGroup && peer?.online == true)

    Scaffold(
        topBar = {
          Column {
            TopAppBar(
                expandedHeight = 54.dp, // compact header (was 64 dp)
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                title = {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            enabled = !isSaved,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenInfo,
                        ),
                    ) {
                        Avatar(
                            title, uin, peer?.avatarUrl ?: group?.avatarUrl,
                            !isGroup && peer?.online == true && !isSaved, 36.dp, isGroup = isGroup, isChannel = isChannel,
                            isSelf = isSaved,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                com.icqnew.app.presentation.components.NameLine(
                                    title, verified = peer?.verified == true || group?.verified == true,
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false), bot = uin == com.icqnew.app.domain.BOT_UIN,
                                    premium = peer?.premium == true,
                                )
                                if (prefs.muted) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Filled.NotificationsOff, null, Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // one fixed-height line: the name no longer jumps up and down when «печатает…» appears
                            if (subtitle.isNotEmpty() || typing.isNotEmpty()) Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterStart) {
                                if (typing.isNotEmpty() && !isSaved && !isChannel) {
                                    com.icqnew.app.presentation.components.TypingLabel(subtitle, MaterialTheme.colorScheme.primary)
                                } else {
                                    Text(
                                        subtitle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
                                        color = if (highlight && !isSaved) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    }
                },
                actions = {
                    if (!isGroup && !prefs.blocked && !isSaved && !isBotChat && !isService) {
                        IconButton(onClick = { call(true) }) { Icon(IcqIcons.Video, "Видеозвонок", Modifier.size(22.dp)) }
                        IconButton(onClick = { call(false) }) { Icon(IcqIcons.Calls, "Звонок", Modifier.size(19.dp)) }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Меню") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(if (isChannel) "О канале" else if (isGroup) "Информация о группе" else "Профиль")
                            },
                            leadingIcon = { Icon(Icons.Filled.Info, null) },
                            onClick = { menu = false; onOpenInfo() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (prefs.muted) "Включить уведомления" else "Без звука") },
                            leadingIcon = {
                                Icon(if (prefs.muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff, null)
                            },
                            onClick = { menu = false; vm.setMuted(!prefs.muted) },
                        )
                        DropdownMenuItem(
                            text = { Text("Фон чата") },
                            leadingIcon = { Icon(Icons.Filled.Photo, null) },
                            onClick = { menu = false; pickWallpaper = true },
                        )
                        if (!isChannel && peer?.isSelf != true && uin != com.icqnew.app.domain.BOT_UIN) {
                            DropdownMenuItem(
                                text = { Text(if (ttl > 0) "Автоудаление: " + ttlLabel(ttl) else "Автоудаление") },
                                leadingIcon = { Icon(Icons.Filled.Timer, null) },
                                onClick = { menu = false; vm.loadTtl(); pickTtl = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Очистить историю") },
                            leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                            onClick = { menu = false; confirmClear = true },
                        )
                        if (!isGroup) {
                            DropdownMenuItem(
                                text = { Text(if (prefs.blocked) "Разблокировать" else "Заблокировать") },
                                leadingIcon = { Icon(Icons.Filled.Block, null) },
                                onClick = { menu = false; vm.setBlocked(!prefs.blocked) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
            // «Вернуться к звонку» and the playing voice: right under the chat header
            com.icqnew.app.presentation.components.AppTopBars(
                container.callManager, container.voicePlayer, inline = true,
            ) { p -> if (p != uin) container.openChatRequest.value = p }
          }
        },
        containerColor = chatBackground(),
        // max(navigation bar, keyboard) at the bottom — not both added together (that left an empty strip).
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                com.icqnew.app.presentation.components.WallpaperBackground(wallpaper, Modifier.matchParentSize())
                if (messages.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        com.icqnew.app.presentation.emoji.EmojiImage("👋", 96.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (isChannel) "В канале пока нет публикаций" else "Напишите первое сообщение",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        items(rows, key = { it.key }) { row ->
                            val flash by androidx.compose.animation.animateColorAsState(
                                if (row is ListRow.Msg && row.m.id == highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                else Color.Transparent,
                                androidx.compose.animation.core.tween(400), label = "flash",
                            )
                            Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220), fadeOutSpec = androidx.compose.animation.core.tween(160)).background(flash, RoundedCornerShape(12.dp))) {
                            when (row) {
                                is ListRow.Day -> DayHeader(row.ts)
                                is ListRow.Call -> CallLine(row.c) { video ->
                                    call(video)
                                }
                                is ListRow.Msg -> {
                                    val m = row.m
                                    if (m.kind == MessageKind.SYSTEM) {
                                        SystemLine(m.text)
                                    } else {
                                        val isBot = m.kind == MessageKind.BOT
                                        val showSender = isBot || (isGroup && !isChannel && !m.fromMe)
                                        CompositionLocalProvider(LocalStoryFile provides storyLoader) {
                                        MessageRow(
                                            m = m,
                                            firstOfGroup = row.firstOfGroup,
                                            lastOfGroup = row.lastOfGroup,
                                            showSender = showSender,
                                            sender = if (isBot) botUser else if (isGroup) members[m.fromUin] else null,
                                            onSwipeReply = if (canWrite) ({ vm.startReply(m) }) else null,
                                            signature = if (isChannel && group?.settings?.signatures == true && !isBot)
                                                members[m.fromUin]?.name else null,
                                            reply = m.replyTo?.let { byServerId[it] },
                                            replyName = m.replyTo?.let { byServerId[it] }?.let { vm.senderName(it) },
                                            voice = voice,
                                            onVoice = { vm.playVoice(m) },
                                            onVoiceSeek = { f -> vm.seekVoice(m, f) },
                                            onVoiceSpeed = { vm.voiceSpeed() },
                                            onImage = { f -> viewer = f to m },
                                            loadImage = { vm.imageFile(m) },
                                            onFile = { fileViewer = m },
                                            onLongPress = { if (!m.deleted) actionsFor = m else deleting = m },
                                            onReact = { e -> vm.react(m, e) },
                                            onReplyClick = { sid ->
                                                indexOfServerId[sid]?.let { i ->
                                                    scope.launch { listState.animateScrollToItem(i) }
                                                    highlighted = byServerId[sid]?.id
                                                }
                                            },
                                            onSenderClick = { if (!isBot) onOpenProfile(m.fromUin) },
                                            onFailed = { failedFor = m },
                                            onLink = { tag, value -> onLinkTap(tag, value) },
                                            loadPreview = { url -> vm.linkPreview(url) },
                                            videoSource = { vm.streamSource(m) },
                                            onVote = if (m.kind == MessageKind.POLL) ({ opts -> vm.vote(m, opts) }) else null,
                                            channelPost = isChannel && !isBot,
                                            onPlayed = { vm.markPlayed(m) },
                                        )
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
                // "@" button: jumps to the messages where somebody tagged me (last 2 days)
                val myUn by vm.myUsername.collectAsStateWithLifecycle()
                val mentionRows = remember(rows, myUn) {
                    if (myUn.isBlank()) emptyList()
                    else {
                        val re = Regex("(?<![\\w@])@" + Regex.escape(myUn) + "(?!\\w)", RegexOption.IGNORE_CASE)
                        val since = System.currentTimeMillis() - 2 * 86_400_000L
                        rows.mapIndexedNotNull { i, r ->
                            if (r is ListRow.Msg && !r.m.fromMe && !r.m.deleted && r.m.ts > since && re.containsMatchIn(r.m.text)) i to r.m.id
                            else null
                        }
                    }
                }
                // only mentions I had not read when I opened the chat; one that has been on screen is done
                val unreadAtOpen = remember(uin) { mutableStateOf<Set<Long>?>(null) }
                LaunchedEffect(uin, messages.isNotEmpty()) {
                    if (unreadAtOpen.value == null && messages.isNotEmpty()) {
                        unreadAtOpen.value = messages.filter { !it.fromMe && !it.isRead }.map { it.id }.toSet()
                    }
                }
                var visitedMentions by remember(uin) { mutableStateOf(setOf<Long>()) }
                LaunchedEffect(mentionRows) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index }.toSet() }.collect { vis ->
                        val seen = mentionRows.filter { it.first in vis }.map { it.second }
                        if (seen.any { it !in visitedMentions }) visitedMentions = visitedMentions + seen
                    }
                }
                val pendingMentions = mentionRows.filter { it.second !in visitedMentions && it.second in (unreadAtOpen.value ?: emptySet()) }
                androidx.compose.animation.AnimatedVisibility(
                    visible = pendingMentions.isNotEmpty(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 70.dp),
                    enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
                ) {
                    BadgedBox(badge = { if (pendingMentions.size > 1) Badge { Text(pendingMentions.size.toString()) } }) {
                        SmallFloatingActionButton(
                            onClick = {
                                // the oldest one first (largest index in the reversed list)
                                val target = pendingMentions.maxByOrNull { it.first } ?: return@SmallFloatingActionButton
                                visitedMentions = visitedMentions + target.second
                                scope.launch { listState.animateScrollToItem(target.first) }
                                highlighted = target.second
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White,
                            shape = CircleShape,
                        ) { Text("@", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                    }
                }
                val showDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
                if (showDown) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ) { Icon(Icons.Filled.KeyboardArrowDown, "Вниз") }
                }
            }

            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column {
                    when {
                        isService -> Text(
                            "Служебные уведомления ICQ: коды входа и новые устройства",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                        )
                        !isGroup && prefs.blocked -> BottomAction("Разблокировать пользователя") { vm.setBlocked(false) }
                        isChannel && !iAmAdmin -> {
                            BottomAction(if (prefs.muted) "Включить уведомления" else "Отключить уведомления") {
                                vm.setMuted(!prefs.muted)
                            }
                        }
                        isGroup && iAmMuted && !iAmAdmin -> {
                            Text(
                                if (mutedUntil < 0) "Администратор запретил вам писать в этой группе"
                                else "Вы сможете писать после " + com.icqnew.app.presentation.components.formatClock(mutedUntil),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                            )
                        }
                        else -> {
                            replyTo?.let { r ->
                                ComposerBanner(
                                    icon = { Icon(Icons.AutoMirrored.Filled.Reply, null, tint = MaterialTheme.colorScheme.primary) },
                                    title = "Ответ: " + vm.senderName(r),
                                    text = shortPreview(r),
                                    onClose = vm::cancelReplyOrEdit,
                                )
                            }
                            editing?.let { e ->
                                ComposerBanner(
                                    icon = { Icon(Icons.Filled.Edit, null, tint = MaterialTheme.colorScheme.primary) },
                                    title = "Редактирование",
                                    text = e.text,
                                    onClose = vm::cancelReplyOrEdit,
                                )
                            }
                            // the input bar stays composed while recording, so the finger that holds the mic
                            // button keeps being tracked (release = send, slide left = cancel)
                            Box(contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.graphicsLayer { alpha = if (recording) 0f else 1f }) {
                                InputBar(
                                    text = input,
                                    showPanel = showPanel,
                                    editing = editing != null,
                                    focus = focus,
                                    onText = vm::onInput,
                                    onToggleEmoji = ::toggleEmoji,
                                    onFieldFocused = { if (showPanel) showPanel = false },
                                    onPickMedia = {
                                        mediaPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                        )
                                    },
                                    onCamera = {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                            == PackageManager.PERMISSION_GRANTED
                                        ) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                                    },
                                    onPickFile = { filePicker.launch("*/*") },
                                    members = if (isGroup && !isChannel) members.values.toList() else emptyList(),
                                    onVideoNote = {
                                        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                                            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                                        if (need.isEmpty()) openVideoNote() else videoNotePermission.launch(need.toTypedArray())
                                    },
                                    onSend = vm::send,
                                    onMicDown = {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                            == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            vm.startRecording().also { ok ->
                                                if (!ok) Toast.makeText(context, "Не удалось начать запись", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                            false
                                        }
                                    },
                                    onMicUp = { cancel -> if (recording) vm.finishRecording(!cancel) },
                                    onPoll = if (isGroup && canWrite) ({ creatingPoll = true }) else null,
                                    onGallery = { attachSheet = true },
                                )
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = recording,
                                    enter = androidx.compose.animation.slideInHorizontally { it / 3 } + androidx.compose.animation.fadeIn(),
                                    exit = androidx.compose.animation.fadeOut(),
                                ) {
                                    RecordingBar(
                                        levels = recLevels,
                                        onCancel = { vm.finishRecording(false) },
                                        onSend = { vm.finishRecording(true) },
                                    )
                                }
                            }
                            if (showPanel && !imeVisible) {
                                EmojiPanel(
                                    height = panelHeight,
                                    onEmoji = vm::appendEmoji,
                                    onSticker = { vm.sendSticker(it) },
                                    onBackspace = vm::backspace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- sheets & dialogs ----

    actionsFor?.let { m ->
        MessageActionsSheet(
            m = m,
            canDeleteForAll = isGroup && iAmAdmin,
            onDismiss = { actionsFor = null },
            onReact = { e -> vm.react(m, e); actionsFor = null },
            onReply = if (canWrite) ({ vm.startReply(m); actionsFor = null }) else null,
            onCopy = { copy(m.text); actionsFor = null },
            onForward = { forwarding = m; actionsFor = null },
            onEdit = { vm.startEdit(m); actionsFor = null },
            onDelete = { deleting = m; actionsFor = null },
            onSave = {
                vm.saveFile(m) { ok ->
                    Toast.makeText(context, if (ok) "Загрузка началась" else "Не удалось скачать", Toast.LENGTH_SHORT)
                        .show()
                }
                actionsFor = null
            },
        )
    }

    forwarding?.let { m ->
        ForwardDialog(
            targets = forwardTargets.filter { !it.isChannel || it.uin == uin },
            onDismiss = { forwarding = null },
            onSend = { list ->
                vm.forward(m, list)
                forwarding = null
                if (list.size == 1 && list[0] != uin) onOpenChat(list[0])
            },
        )
    }

    deleting?.let { m ->
        val canForAll = m.serverId != null && !m.deleted && (m.fromMe || (isGroup && iAmAdmin))
        var forAll by remember(m.id) { mutableStateOf(canForAll) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить сообщение?") },
            text = {
                if (canForAll) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { forAll = !forAll },
                    ) {
                        Checkbox(checked = forAll, onCheckedChange = { forAll = it })
                        Text(if (isGroup) "Удалить у всех" else "Удалить также у собеседника")
                    }
                } else Text("Сообщение будет удалено только у вас.")
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(m, forAll && canForAll); deleting = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }

    failedFor?.let { m ->
        AlertDialog(
            onDismissRequest = { failedFor = null },
            title = { Text("Не отправлено") },
            text = { Text(m.failReason ?: "Сообщение не удалось отправить.") },
            confirmButton = {
                TextButton(onClick = { vm.retry(m); failedFor = null }) { Text("Повторить") }
            },
            dismissButton = {
                TextButton(onClick = { vm.delete(m, false); failedFor = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }


    if (pickTtl) {
        AlertDialog(
            onDismissRequest = { pickTtl = false },
            icon = { Icon(Icons.Filled.Timer, null) },
            title = { Text("Автоудаление сообщений") },
            text = {
                Column {
                    Text(
                        "Новые сообщения в этом чате исчезнут у всех участников через выбранное время.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(0, 3600, 86400, 2 * 86400, 7 * 86400, 30 * 86400).forEach { sec ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { vm.setTtl(sec); pickTtl = false }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = ttl == sec, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(if (sec == 0) "Выключено" else ttlLabel(sec))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTtl = false }) { Text("Закрыть") } },
        )
    }
    if (pickWallpaper) {
        com.icqnew.app.presentation.components.WallpaperPicker(
            current = prefs.wallpaper,
            allowReset = true,
            onPick = { vm.setWallpaper(it) },
            onDismiss = { pickWallpaper = false },
        )
    }

    if (confirmClear) {
        // Private chat: optionally also for the other person. Group: the creator may clear it for everyone.
        val canForAll = (!isGroup && !isSaved) || (isGroup && group?.iAmOwner == true)
        var forAll by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = {
                Column {
                    Text(if (forAll) "Сообщения будут удалены безвозвратно." else "Сообщения будут удалены с этого телефона.")
                    if (canForAll) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { forAll = !forAll },
                        ) {
                            Checkbox(checked = forAll, onCheckedChange = { forAll = it })
                            Text(if (isGroup) "Очистить у всех участников" else "Также удалить у ${peer?.name ?: "собеседника"}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(forAll && canForAll); confirmClear = false }) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }

    phoneCheck?.let { number ->
        PhoneLookupDialog(
            number = number,
            lookup = { vm.lookupPhone(it) },
            onWrite = { u -> phoneCheck = null; onOpenChat(u.uin) },
            onProfile = { u -> phoneCheck = null; onOpenProfile(u.uin) },
            onCall = {
                phoneCheck = null
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { c -> c.isDigit() || c == '+' }))) }
            },
            onDismiss = { phoneCheck = null },
        )
    }

    if (attachSheet) {
        AttachSheet(
            onDismiss = { attachSheet = false },
            onSend = { uris -> uris.forEach { sendUri(it) } },
            onEditPhoto = { editPhoto = it },
            onCamera = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            onSystemGallery = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onFile = { filePicker.launch("*/*") },
            onVideoNote = {
                val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (need.isEmpty()) openVideoNote() else videoNotePermission.launch(need.toTypedArray())
            },
            onPoll = if (isGroup && canWrite) ({ creatingPoll = true }) else null,
        )
    }
    if (pendingFiles.isNotEmpty()) {
        SendPreviewSheet(
            items = pendingFiles,
            onEdit = { p -> reEdit = p },
            onRemove = { p -> pendingFiles = pendingFiles - p; p.file.delete() },
            onCancel = { pendingFiles.forEach { it.file.delete() }; pendingFiles = emptyList() },
            onSend = { caption ->
                val list = pendingFiles
                pendingFiles = emptyList()
                list.forEachIndexed { i, p -> vm.sendFile(p.file, p.name, p.mime, if (i == 0) caption else "") }
            },
        )
    }
    LaunchedEffect(recordingNote) {
        while (recordingNote) {
            vm.notifyRecording("video")
            delay(2_500)
        }
    }
    if (recordingNote) {
        VideoNoteRecorder(
            onDismiss = { recordingNote = false },
            onDone = { f, dur -> recordingNote = false; vm.sendVideoNote(f, dur) },
        )
    }
    if (creatingPoll) {
        CreatePollDialog(
            onDismiss = { creatingPoll = false },
            onCreate = { q, opts, multiple, anonymous -> creatingPoll = false; vm.sendPoll(q, opts, multiple, anonymous) },
        )
    }

    fileViewer?.let { m ->
        FileViewer(
            m = m,
            loadFile = { vm.imageFile(m) },
            streamSource = { vm.streamSource(m) },
            onSave = {
                vm.saveFile(m) { ok ->
                    Toast.makeText(context, if (ok) "Загрузка началась" else "Не удалось скачать", Toast.LENGTH_SHORT)
                        .show()
                }
            },
            onClose = { fileViewer = null },
        )
    }

    viewer?.let { (f, m) ->
        ImageViewer(
            file = f,
            onClose = { viewer = null },
            onSave = {
                vm.saveFile(m) { ok ->
                    Toast.makeText(context, if (ok) "Загрузка началась" else "Не удалось скачать", Toast.LENGTH_SHORT)
                        .show()
                }
            },
        )
    }
}

fun subscribers(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> "подписчик"
        m10 in 2..4 && m100 !in 12..14 -> "подписчика"
        else -> "подписчиков"
    }
    return "$n $w"
}

@Composable
private fun BottomAction(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

// ---- input ------------------------------------------------------------------

@Composable
private fun InputBar(
    text: String,
    showPanel: Boolean,
    editing: Boolean,
    focus: FocusRequester,
    onText: (String) -> Unit,
    onToggleEmoji: () -> Unit,
    onFieldFocused: () -> Unit,
    onPickMedia: () -> Unit,
    onCamera: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onMicDown: () -> Boolean,
    onMicUp: (cancel: Boolean) -> Unit,
    members: List<User> = emptyList(),
    onVideoNote: () -> Unit = {},
    onPoll: (() -> Unit)? = null,
    onGallery: () -> Unit = {},
) {
    var attachMenu by remember { mutableStateOf(false) }
    // the field keeps the selection (needed for the formatting menu and @mentions)
    var tfv by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (tfv.text == text) tfv else TextFieldValue(text, TextRange(text.length)).also { tfv = it }
    fun change(v: TextFieldValue) { tfv = v; onText(v.text) }
    val toolbar = remember { FormattingToolbar() }

    // ---- @mention suggestions (groups) ----
    val query: String? = remember(value) {
        val before = value.text.substring(0, value.selection.start.coerceIn(0, value.text.length))
        val at = before.lastIndexOf('@')
        if (at < 0 || (at > 0 && !before[at - 1].isWhitespace())) null
        else before.substring(at + 1).takeIf { q -> q.length <= 32 && q.none { it.isWhitespace() } }
    }
    // only people with an @username can be tagged (the text always gets @username, never the name)
    val suggestions = if (query == null || members.isEmpty()) emptyList() else members.filter { u ->
        u.uin != com.icqnew.app.domain.BOT_UIN && !u.username.isNullOrBlank() &&
            (u.name.contains(query, true) || u.username!!.contains(query, true))
    }.take(5)
    Column {
    androidx.compose.animation.AnimatedVisibility(visible = suggestions.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
            suggestions.forEach { u ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val pos = value.selection.start
                        val at = value.text.lastIndexOf('@', (pos - 1).coerceAtLeast(0))
                        val tag = "@" + u.username + " "
                        val nt = value.text.substring(0, at) + tag + value.text.substring(pos)
                        change(TextFieldValue(nt, TextRange(at + tag.length)))
                    }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(u.name, u.uin, u.avatarUrl, u.online, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("@" + u.username, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        EmojiText(u.name, maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    // one compact selection bar (no popup: a popup steals focus and made the menu blink)
    androidx.compose.animation.AnimatedVisibility(
        visible = !value.selection.collapsed || toolbar.shown,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Composable
            fun chip(label: String, style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle(), onClick: () -> Unit) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Text(label, style = style, fontSize = 14.sp) }
            }
            if (!value.selection.collapsed) {
                toolbar.onCut?.let { f -> chip("Вырезать") { f(); toolbar.hide() } }
                toolbar.onCopy?.let { f -> chip("Копировать") { f(); toolbar.hide() } }
            }
            toolbar.onPaste?.let { f -> chip("Вставить") { f(); toolbar.hide() } }
            toolbar.onSelectAll?.let { f -> chip("Всё") { f() } }
            if (!value.selection.collapsed) {
                Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
                FORMATS.forEach { (label, mark) ->
                    chip(label, formatPreviewStyle(mark)) { change(applyFormat(value, mark)); toolbar.hide() }
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onToggleEmoji, modifier = Modifier.size(42.dp)) {
            Icon(
                if (showPanel) Icons.Filled.Keyboard else Icons.Outlined.EmojiEmotions,
                if (showPanel) "Клавиатура" else "Смайлы и стикеры",
                tint = if (showPanel) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.weight(1f)) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalTextToolbar provides toolbar) {
        // compact field (42 dp instead of the 56 dp Material one)
        val onSurface = MaterialTheme.colorScheme.onSurface
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = { change(it) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .focusRequester(focus)
                .onFocusChanged { if (it.isFocused) onFieldFocused() },
            // fixed line height: the field no longer jumps between the hint, Rubik text and «fancy font» text
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp, lineHeight = 22.sp, color = onSurface,
                fontFamily = com.icqnew.app.presentation.theme.fontFor(value.text) ?: com.icqnew.app.presentation.theme.Rubik,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
                ),
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 6,
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(
                            "Сообщение", fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = com.icqnew.app.presentation.theme.Rubik,
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            ),
                        )
                    }
                    inner()
                }
            },
        )
        }
        }
        if (!editing) {
            Box {
                IconButton(onClick = onGallery, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Filled.AttachFile, "Вложение", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Фото или видео") },
                        leadingIcon = { Icon(Icons.Filled.Photo, null) },
                        onClick = { attachMenu = false; onPickMedia() },
                    )
                    DropdownMenuItem(
                        text = { Text("Камера") },
                        leadingIcon = { Icon(Icons.Filled.CameraAlt, null) },
                        onClick = { attachMenu = false; onCamera() },
                    )
                    DropdownMenuItem(
                        text = { Text("Видеосообщение") },
                        leadingIcon = { Icon(Icons.Filled.Videocam, null) },
                        onClick = { attachMenu = false; onVideoNote() },
                    )
                    DropdownMenuItem(
                        text = { Text("Файл (до 4 ГБ)") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null) },
                        onClick = { attachMenu = false; onPickFile() },
                    )
                }
            }
        }
        // mic <-> send swap with a little pop
        androidx.compose.animation.AnimatedContent(
            targetState = text.isBlank() && !editing,
            transitionSpec = {
                (androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.55f), initialScale = 0.4f) +
                    androidx.compose.animation.fadeIn()) togetherWith
                    (androidx.compose.animation.scaleOut(targetScale = 0.4f) + androidx.compose.animation.fadeOut())
            },
            label = "send",
        ) { showMic ->
            if (showMic) {
                MicButton(onMicDown = onMicDown, onMicUp = onMicUp, onVideoNote = onVideoNote)
            } else {
                FilledIconButton(onClick = onSend, modifier = Modifier.size(42.dp)) {
                    Icon(if (editing) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send, "Отправить")
                }
            }
        }
    }
    }
}

/** label -> marker (applied around the selected text) */
private val FORMATS = listOf(
    "Жирный" to "**", "Курсив" to "__", "Моно" to "`", "Зачёркнутый" to "~~",
    "Подчёркнутый" to "--", "Скрытый" to "||", "Код" to "```", "Обычный" to "",
)

private fun formatPreviewStyle(mark: String): androidx.compose.ui.text.TextStyle = when (mark) {
    "**" -> androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold)
    "__" -> androidx.compose.ui.text.TextStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    "`", "```" -> androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    "~~" -> androidx.compose.ui.text.TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
    "--" -> androidx.compose.ui.text.TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
    else -> androidx.compose.ui.text.TextStyle()
}

/** Wraps the selection with the marker ("" removes all markers from the selection). */
private fun applyFormat(v: TextFieldValue, mark: String): TextFieldValue {
    val start = v.selection.min
    val end = v.selection.max
    val sel = v.text.substring(start, end)
    val inner = if (mark.isEmpty()) com.icqnew.app.presentation.emoji.RichFormat.plain(sel) else sel
    val wrapped = if (mark.isEmpty()) inner else mark + inner + mark
    val nt = v.text.substring(0, start) + wrapped + v.text.substring(end)
    return TextFieldValue(nt, TextRange(start + wrapped.length))
}

/** Our own text selection toolbar, so the menu can show the formatting items. */
private class FormattingToolbar : androidx.compose.ui.platform.TextToolbar {
    var shown by mutableStateOf(false)
    var onCopy: (() -> Unit)? by mutableStateOf(null)
    var onPaste: (() -> Unit)? by mutableStateOf(null)
    var onCut: (() -> Unit)? by mutableStateOf(null)
    var onSelectAll: (() -> Unit)? by mutableStateOf(null)

    override val status: androidx.compose.ui.platform.TextToolbarStatus
        get() = if (shown) androidx.compose.ui.platform.TextToolbarStatus.Shown else androidx.compose.ui.platform.TextToolbarStatus.Hidden

    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        onCopy = onCopyRequested
        onPaste = onPasteRequested
        onCut = onCutRequested
        onSelectAll = onSelectAllRequested
        shown = true
    }

    override fun hide() {
        shown = false
    }
}

/**
 * Telegram-like record button: a tap switches voice <-> round video,
 * holding records (release = send, slide left = cancel).
 */
@Composable
private fun MicButton(onMicDown: () -> Boolean, onMicUp: (Boolean) -> Unit, onVideoNote: () -> Unit) {
    var videoMode by rememberSaveable { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val cancelDist = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    val primary = MaterialTheme.colorScheme.primary
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (holding) 1.45f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f), label = "mic",
    )
    val latestDown by rememberUpdatedState(onMicDown)
    val latestUp by rememberUpdatedState(onMicUp)
    val latestVideo by rememberUpdatedState(onVideoNote)
    Box(
        Modifier
            .size(42.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (holding) primary else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var released = false
                    withTimeoutOrNull(260) {
                        waitForUpOrCancellation()
                        released = true
                    }
                    if (released) { // quick tap: switch mode
                        videoMode = !videoMode
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        return@awaitEachGesture
                    }
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (videoMode) { // the camera app records the circle
                        latestVideo()
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    if (!latestDown()) return@awaitEachGesture
                    holding = true
                    var dx = 0f
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
                        dx = c.position.x - down.position.x
                        c.consume()
                        if (!c.pressed) break
                    }
                    holding = false
                    latestUp(dx < -cancelDist)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = videoMode,
            transitionSpec = {
                (androidx.compose.animation.scaleIn(initialScale = 0.3f) + androidx.compose.animation.fadeIn()) togetherWith
                    (androidx.compose.animation.scaleOut(targetScale = 0.3f) + androidx.compose.animation.fadeOut())
            },
            label = "micMode",
        ) { video ->
            val tint = if (holding) Color.White else primary
            if (video) {
                Box(
                    Modifier.size(24.dp).border(2.dp, tint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Videocam, "Видеосообщение", tint = tint, modifier = Modifier.size(14.dp)) }
            } else {
                Icon(Icons.Filled.Mic, "Голосовое сообщение", tint = tint)
            }
        }
    }
}

@Composable
private fun RecordingBar(levels: List<Float>, onCancel: () -> Unit, onSend: () -> Unit) {
    var sec by remember { mutableIntStateOf(0) }
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            blink = !blink
            if (!blink) sec++
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Delete, "Отмена", tint = MaterialTheme.colorScheme.error)
        }
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (blink) MaterialTheme.colorScheme.error else Color.Transparent)
        )
        Spacer(Modifier.width(8.dp))
        Text("%d:%02d".format(sec / 60, sec % 60), fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        Text("‹ влево — отмена", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        // live bars: they grow with your voice
        Row(
            Modifier.weight(1f).height(34.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bars = (List((40 - levels.size).coerceAtLeast(0)) { 0f } + levels).takeLast(40)
            bars.forEach { lv ->
                val h by androidx.compose.animation.core.animateFloatAsState(0.12f + 0.88f * lv, label = "bar")
                Box(
                    Modifier.width(3.dp).fillMaxHeight(h).clip(RoundedCornerShape(2.dp))
                        .background(primary.copy(alpha = 0.45f + 0.55f * lv))
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // a big pulsing red mic under the finger: clearly shows that the voice is being recorded
        val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "rec").animateFloat(
            1f, 1.18f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(650), androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "recPulse",
        )
        Box(
            Modifier.size(52.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(CircleShape)
                .background(Color(0xFFFF3B30)).clickable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Mic, "Отправить", tint = Color.White, modifier = Modifier.size(26.dp)) }
    }
}





private fun ttlLabel(sec: Int): String = when (sec) {
    3600 -> "1 час"
    86400 -> "1 день"
    2 * 86400 -> "2 дня"
    7 * 86400 -> "1 неделя"
    30 * 86400 -> "1 месяц"
    else -> "выкл."
}
