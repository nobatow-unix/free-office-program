package com.icqnew.app.presentation.stories

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.icqnew.app.domain.StoryGroup
import com.icqnew.app.domain.User
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.NameLine
import com.icqnew.app.presentation.components.formatListTime
import com.icqnew.app.presentation.emoji.EmojiText
import kotlinx.coroutines.launch
import java.io.File

private val RING = Brush.sweepGradient(listOf(Color(0xFF22FF01), Color(0xFF00CB66), Color(0xFF22FF01)))
private val STORY_REACTIONS = listOf("❤️", "🔥", "😂", "😮", "😢", "👍")

/** Row of round story avatars above the chat list. The first one is "My story". */
@Composable
fun StoriesRow(
    groups: List<StoryGroup>,
    me: User?,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val myIndex = groups.indexOfFirst { it.user.uin == me?.uin }
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp)) {
                Box(Modifier.clickable { if (myIndex >= 0) onOpen(myIndex) else onAdd() }) {
                    Ring(seen = myIndex < 0) { Avatar(me?.name ?: "?", me?.uin ?: 0, me?.avatarUrl, false, 58.dp, storyRing = false) }
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                            .clickable(onClick = onAdd),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, "Добавить", tint = Color.White, modifier = Modifier.size(14.dp)) }
                }
                Spacer(Modifier.height(4.dp))
                Text("Моя история", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        itemsIndexed(groups) { i, g ->
            if (g.user.uin == me?.uin) return@itemsIndexed
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable { onOpen(i) }) {
                Ring(seen = g.allSeen) { Avatar(g.user.name, g.user.uin, g.user.avatarUrl, false, 58.dp, storyRing = false) }
                Spacer(Modifier.height(4.dp))
                EmojiText(g.user.name.substringBefore(' '), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Ring(seen: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier.size(68.dp).clip(CircleShape)
            .then(
                if (seen) Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                else Modifier.border(3.dp, RING, CircleShape)
            )
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Full-screen story player: tap right = next, left = back, hold = pause, swipe down = close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewer(groups: List<StoryGroup>, start: Int, myUin: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer()
    val scope = rememberCoroutineScope()
    var gi by remember { mutableIntStateOf(start) }
    val group = groups.getOrNull(gi)
    if (group == null || group.stories.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var si by remember(gi) {
        mutableIntStateOf(group.stories.indexOfFirst { !it.seen }.let { if (it < 0 || group.user.uin == myUin) 0 else it })
    }
    val story = group.stories[si.coerceIn(0, group.stories.lastIndex)]
    val mine = group.user.uin == myUin
    var file by remember(story.id) { mutableStateOf<File?>(null) }
    var paused by remember { mutableStateOf(false) }
    var viewers by remember { mutableStateOf<List<User>?>(null) }
    val progress = remember(story.id) { Animatable(0f) }

    fun next() {
        if (si < group.stories.lastIndex) si++
        else if (gi < groups.lastIndex) gi++
        else onClose()
    }

    fun prev() {
        if (si > 0) si--
        else if (gi > 0) gi--
    }

    LaunchedEffect(story.id) {
        file = container.mediaCache.file(story.attId, "story_${story.id}.jpg")
        if (!mine && !story.seen) container.chatRepository.markStorySeen(story.id)
    }
    var commentsOpen by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(story.id, file, paused, viewers, commentsOpen, typing) {
        if (file != null && !paused && viewers == null && !commentsOpen && !typing) {
            val left = ((1f - progress.value) * 5_500).toInt().coerceAtLeast(1)
            progress.animateTo(1f, tween(left, easing = LinearEasing))
            next()
        }
    }
    BackHandler { onClose() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(story.id) {
                    detectTapGestures(
                        onTap = { o -> if (o.x < size.width / 3f) prev() else next() },
                        onPress = {
                            paused = true
                            tryAwaitRelease()
                            paused = false
                        },
                    )
                }
                .pointerInput(story.id, mine) {
                    detectVerticalDragGestures { _, dy ->
                        if (dy > 40) onClose()
                        else if (dy < -40 && mine && viewers == null) {
                            scope.launch { viewers = runCatching { container.chatRepository.storyViewers(story.id) }.getOrDefault(emptyList()) }
                        }
                    }
                },
        ) {
            androidx.compose.animation.Crossfade(targetState = file, label = "story", modifier = Modifier.fillMaxSize()) { f ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (f != null) {
                        AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
            // top: progress bars + author
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .statusBarsPadding().padding(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.stories.forEachIndexed { i, _ ->
                        val p = when {
                            i < si -> 1f
                            i == si -> progress.value
                            else -> 0f
                        }
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Color.White))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(group.user.name, group.user.uin, group.user.avatarUrl, false, 38.dp, storyRing = false)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        NameLine(if (mine) "Моя история" else group.user.name, group.user.verified, color = Color.White, fontSize = 15.sp)
                        Text(formatListTime(story.ts), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                    if (mine) {
                        IconButton(onClick = {
                            scope.launch {
                                runCatching { container.chatRepository.deleteStory(story.id) }
                                onClose()
                            }
                        }) { Icon(Icons.Filled.Delete, "Удалить", tint = Color.White) }
                    }
                    IconButton(onClick = {
                        // share the picture itself + a link to the author
                        val f = file
                        val text = "История " + group.user.name + " в ICQ New" +
                            (group.user.username?.takeIf { it.isNotBlank() }?.let { "\n" + com.icqnew.app.presentation.IcqLinks.user(it) } ?: "")
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_TEXT, text)
                        if (f != null) {
                            runCatching {
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                                send.setType("image/*").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }.onFailure { send.setType("text/plain") }
                        } else send.setType("text/plain")
                        context.startActivity(android.content.Intent.createChooser(send, "Поделиться историей"))
                    }) { Icon(Icons.Filled.Share, "Поделиться", tint = Color.White) }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
                }
            }
            // bottom: caption + views
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (story.text.isNotBlank()) {
                    EmojiText(story.text, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                }
                if (!mine) {
                    var picked by remember(story.id) { mutableStateOf(story.myReaction) }
                    Row(
                        Modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        STORY_REACTIONS.forEach { e ->
                            val sel = picked == e
                            val scale by androidx.compose.animation.core.animateFloatAsState(
                                if (sel) 1.3f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f), label = "r",
                            )
                            Box(
                                Modifier.size(44.dp).graphicsLayer(scaleX = scale, scaleY = scale).clip(CircleShape)
                                    .background(if (sel) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                                    .clickable {
                                        picked = if (sel) "" else e
                                        scope.launch { runCatching { container.chatRepository.reactStory(story.id, picked) } }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { com.icqnew.app.presentation.emoji.EmojiImage(e, 30.dp) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                StoryCommentBar(
                    count = story.comments,
                    canWrite = !mine,
                    onOpen = { commentsOpen = true },
                    onTyping = { typing = it },
                    onSend = { text ->
                        scope.launch {
                            runCatching { container.chatRepository.addStoryComment(story.id, text) }
                                .onSuccess { android.widget.Toast.makeText(context, "Комментарий отправлен", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                if (mine) {
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.15f))
                            .clickable {
                                scope.launch { viewers = runCatching { container.chatRepository.storyViewers(story.id) }.getOrDefault(emptyList()) }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Visibility, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Просмотры: ${story.views}" + if (story.reactions > 0) "  ·  ❤️ ${story.reactions}" else "", color = Color.White)
                    }
                }
            }
        }
    }

    if (commentsOpen) {
        StoryCommentsSheet(
            storyId = story.id, myUin = myUin, storyOwner = group.user.uin,
            onDismiss = { commentsOpen = false },
        )
    }

    viewers?.let { list ->
        ModalBottomSheet(
            onDismissRequest = { viewers = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val reacted = list.count { it.about.isNotBlank() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Visibility, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Просмотры: ${list.size}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (reacted > 0) {
                        Spacer(Modifier.width(12.dp))
                        Text("❤️ $reacted", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) {
                    Text("Пока никто не посмотрел", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(list.size) { idx ->
                        val u = list[idx]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Avatar(u.name, u.uin, u.avatarUrl, false, 42.dp, storyRing = false)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                NameLine(u.name, u.verified, fontWeight = FontWeight.Medium)
                                Text(formatListTime(u.lastSeen), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (u.about.isNotBlank()) {
                                com.icqnew.app.presentation.emoji.EmojiImage(u.about, 28.dp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Caption step after the photo editor. */
@Composable
fun StoryCaptionDialog(onPost: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Новая история") },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(300) }, maxLines = 4,
                    label = { Text("Подпись (необязательно)") },
                )
                Spacer(Modifier.height(6.dp))
                Text("История видна вашим контактам 24 часа.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPost(text) }) { Text("Опубликовать") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}


/** Bottom bar of a story: "write a comment" field (other people's stories) and the 💬 counter. */
@Composable
private fun StoryCommentBar(
    count: Int,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onTyping: (Boolean) -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (canWrite) {
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it.take(500); onTyping(it.isNotEmpty()) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                modifier = Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(22.dp)),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("Комментарий…", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                        inner()
                    }
                },
            )
            if (text.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { onSend(text.trim()); text = ""; onTyping(false) },
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Color.White) }
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.15f)).clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.Comment, "Комментарии", tint = Color.White, modifier = Modifier.size(20.dp))
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** All comments of a story in a pull-up sheet (with a field to add one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryCommentsSheet(storyId: Long, myUin: Long, storyOwner: Long, onDismiss: () -> Unit) {
    val container = LocalContext.current.appContainer()
    val scope = rememberCoroutineScope()
    var list by remember(storyId) { mutableStateOf<List<com.icqnew.app.domain.StoryComment>?>(null) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(storyId) { list = runCatching { container.chatRepository.storyComments(storyId) }.getOrDefault(emptyList()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding()) {
            Text("Комментарии" + (list?.size?.takeIf { it > 0 }?.let { ": $it" } ?: ""), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(8.dp))
            val l = list
            when {
                l == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                l.isEmpty() -> Text(
                    "Пока нет комментариев. Будьте первым!", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(l.size, key = { l[it].id }) { i ->
                        val c = l[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Avatar(c.user.name, c.user.uin, c.user.avatarUrl, false, 36.dp, storyRing = false)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    NameLine(c.user.name, c.user.verified, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.width(6.dp))
                                    Text(formatListTime(c.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                EmojiText(c.text, fontSize = 15.sp)
                            }
                            if (c.user.uin == myUin || storyOwner == myUin) {
                                IconButton(onClick = {
                                    scope.launch {
                                        runCatching { container.chatRepository.deleteStoryComment(storyId, c.id) }
                                        list = list?.filterNot { it.id == c.id }
                                    }
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(500) }, singleLine = false, maxLines = 3,
                    placeholder = { Text("Написать комментарий…") }, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        text = ""
                        scope.launch {
                            runCatching { container.chatRepository.addStoryComment(storyId, t) }.onSuccess { c -> list = (list ?: emptyList()) + c }
                        }
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
