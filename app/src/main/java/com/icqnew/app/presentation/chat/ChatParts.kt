package com.icqnew.app.presentation.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.icqnew.app.presentation.components.VerifiedBadge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shortcut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.icqnew.app.data.local.FileUtils
import com.icqnew.app.data.media.VoicePlayer
import com.icqnew.app.domain.ChatMessage
import com.icqnew.app.domain.MessageKind
import com.icqnew.app.domain.MessageStatus
import com.icqnew.app.domain.User
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.plainClick
import com.icqnew.app.presentation.components.formatClock
import com.icqnew.app.presentation.components.formatDay
import com.icqnew.app.presentation.emoji.Emoji
import com.icqnew.app.presentation.emoji.EmojiImage
import com.icqnew.app.presentation.emoji.EmojiText
import java.io.File
import kotlin.math.abs

val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🔥", "👎", "🙏")

private val nameColors = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFF4511E),
    Color(0xFF00897B), Color(0xFF3949AB), Color(0xFFD81B60), Color(0xFF6D4C41),
)

fun nameColor(uin: Long) = nameColors[(abs(uin) % nameColors.size).toInt()]

@Composable
fun chatBackground(): Color =
    lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary, 0.05f)

fun shortPreview(m: ChatMessage): String = when {
    m.deleted -> "Сообщение удалено"
    m.kind == MessageKind.IMAGE -> "📷 Фото"
    m.kind == MessageKind.FILE -> "📎 " + (m.attName ?: "Файл")
    m.kind == MessageKind.VOICE -> "🎤 Голосовое сообщение"
    m.kind == MessageKind.VIDEO_NOTE -> "⏺ Видеосообщение"
    m.kind == MessageKind.POLL -> "📊 " + m.text
    m.kind == MessageKind.STICKER -> m.text + " Стикер"
    else -> com.icqnew.app.presentation.emoji.RichFormat.plain(m.text)
}

// ---- rows -------------------------------------------------------------------

@Composable
fun DayHeader(ts: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            formatDay(ts),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun SystemLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        EmojiText(
            text,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    m: ChatMessage,
    firstOfGroup: Boolean,
    lastOfGroup: Boolean,
    showSender: Boolean,
    sender: User?,
    reply: ChatMessage?,
    replyName: String?,
    voice: VoicePlayer.State,
    onVoice: () -> Unit,
    onImage: (File) -> Unit,
    loadImage: suspend () -> File?,
    onFile: () -> Unit,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    onReplyClick: (Long) -> Unit,
    onSenderClick: () -> Unit,
    onFailed: () -> Unit,
    onSwipeReply: (() -> Unit)? = null,
    signature: String? = null,
    onLink: (String, String) -> Unit = { _, _ -> },
    loadPreview: (suspend (String) -> com.icqnew.app.domain.LinkPreview?)? = null,
    videoSource: (suspend () -> Pair<String, Map<String, String>>?)? = null,
    onVoiceSeek: ((Float) -> Unit)? = null,
    onVoiceSpeed: () -> Unit = {},
    onVote: ((List<Int>) -> Unit)? = null,
) {
    val mine = m.fromMe
    val bigEmoji = remember(m.text, m.kind, m.deleted) {
        if (!m.deleted && (m.kind == MessageKind.TEXT || m.kind == MessageKind.STICKER) && m.replyTo == null && m.fwdName == null)
            Emoji.onlyEmojiCount(m.text) else 0
    }
    // ---- swipe right to reply ----
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dx = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    val swipe = if (onSwipeReply != null && m.serverId != null && !m.deleted) {
        Modifier.pointerInput(m.id) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (dx.value >= threshold) onSwipeReply()
                    armed = false
                    scope.launch { dx.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                },
                onDragCancel = {
                    armed = false
                    scope.launch { dx.animateTo(0f) }
                },
                onHorizontalDrag = { change, amount ->
                    val nv = (dx.value + amount * 0.6f).coerceIn(0f, threshold * 1.6f)
                    if (nv >= threshold && !armed) {
                        armed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else if (nv < threshold) armed = false
                    scope.launch { dx.snapTo(nv) }
                    change.consume()
                },
            )
        }
    } else Modifier

    Box(Modifier.fillMaxWidth().then(swipe)) {
        if (dx.value > 4f) {
            val k = (dx.value / threshold).coerceIn(0f, 1f)
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(34.dp)
                    .graphicsLayer(scaleX = k, scaleY = k, alpha = k)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Reply, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        }
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(dx.value.roundToInt(), 0) }
                .padding(top = if (firstOfGroup) 5.dp else 1.5.dp),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (showSender) {
                Box(Modifier.width(40.dp)) {
                    if (lastOfGroup) {
                        Box(Modifier.plainClick(onSenderClick)) {
                            Avatar(
                                sender?.name ?: "?", m.fromUin, sender?.avatarUrl, false, 34.dp,
                                isBot = m.kind == MessageKind.BOT,
                            )
                        }
                    }
                }
            }
            if (mine && m.status == MessageStatus.FAILED) {
                IconButton(onClick = onFailed, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ErrorOutline, "Ошибка", tint = MaterialTheme.colorScheme.error)
                }
            }
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (bigEmoji > 0) {
                    Column(
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
                    ) {
                        if (showSender && firstOfGroup) SenderName(sender?.name, m.fromUin, sender?.verified == true, onSenderClick)
                        val size = when {
                            m.kind == MessageKind.STICKER || bigEmoji == 1 -> 120.dp
                            bigEmoji == 2 -> 76.dp
                            else -> 60.dp
                        }
                        Box {
                            EmojiText(
                                m.text, fontSize = (size.value * 0.62f).sp, emojiScale = 1.25f, big = true,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.35f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) { Meta(m, Color.White) }
                        }
                    }
                } else if (m.kind == MessageKind.VIDEO_NOTE && !m.deleted && videoSource != null) {
                    Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        if (showSender && firstOfGroup) SenderName(sender?.name, m.fromUin, sender?.verified == true, onSenderClick)
                        VideoNote(m, videoSource, onOpen = onFile, onLongPress = onLongPress)
                        Box(
                            Modifier.padding(top = 2.dp).clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 6.dp, vertical = 1.dp),
                        ) { Meta(m, Color.White) }
                    }
                } else {
                    Bubble(
                        m, firstOfGroup, lastOfGroup, showSender, sender, reply, replyName, voice,
                        onVoice, onImage, loadImage, onFile, onLongPress, onReplyClick, onSenderClick, signature,
                        onLink, loadPreview, onVoiceSeek, onVoiceSpeed, onReact, onVote,
                    )
                }
                val outside = bigEmoji > 0 || (m.kind == MessageKind.VIDEO_NOTE && !m.deleted && videoSource != null)
                if (outside && m.reactions.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        m.reactions.forEach { r ->
                            val bg = if (r.mine) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(bg)
                                    .border(
                                        1.dp,
                                        if (r.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable { onReact(r.emoji) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                EmojiImage(r.emoji, 18.dp)
                                if (r.count > 1) {
                                    Spacer(Modifier.width(3.dp))
                                    Text(r.count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SenderName(name: String?, uin: Long, verified: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiText(
            name ?: "ID $uin",
            color = nameColor(uin),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (verified || uin == com.icqnew.app.domain.BOT_UIN) {
            Spacer(Modifier.width(3.dp))
            VerifiedBadge(14.dp, bot = uin == com.icqnew.app.domain.BOT_UIN)
        }
    }
}

/** Text followed by the time: on the same line when it fits (like Telegram), otherwise below it. */
@Composable
private fun TextWithMeta(
    text: String,
    color: Color,
    rich: Boolean = false,
    linkColor: Color = Color(0xFF3D9BFF),
    onLink: (String, String) -> Unit = { _, _ -> },
    onLongPress: () -> Unit = {},
    spoilerColor: Color = Color.Unspecified,
    meta: @Composable () -> Unit,
) {
    val lastLine = remember { FloatArray(2) } // [0] = right edge of the last line, [1] = line count
    Layout(
        content = {
            val onLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = { r ->
                lastLine[0] = r.getLineRight(r.lineCount - 1)
                lastLine[1] = r.lineCount.toFloat()
            }
            if (rich) {
                com.icqnew.app.presentation.emoji.RichText(
                    text, color = color, linkColor = linkColor, fontSize = 16.sp, spoilerColor = spoilerColor,
                    onTextLayout = onLayout, onLink = onLink, onLongPress = onLongPress,
                )
            } else {
                EmojiText(text, color = color, fontSize = 16.sp, onTextLayout = onLayout)
            }
            meta()
        },
        measurePolicy = object : androidx.compose.ui.layout.MeasurePolicy {
            // Needed when the bubble sizes itself to its widest child (reply quote above the text).
            override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(
                measurables: List<androidx.compose.ui.layout.IntrinsicMeasurable>, height: Int,
            ): Int = measurables[0].maxIntrinsicWidth(height) + 8.dp.roundToPx() + measurables[1].maxIntrinsicWidth(height)

            override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(
                measurables: List<androidx.compose.ui.layout.IntrinsicMeasurable>, height: Int,
            ): Int = maxOf(measurables[0].minIntrinsicWidth(height), measurables[1].maxIntrinsicWidth(height))

            override fun androidx.compose.ui.layout.MeasureScope.measure(
                measurables: List<androidx.compose.ui.layout.Measurable>, constraints: Constraints,
            ): androidx.compose.ui.layout.MeasureResult {
        val t = measurables[0].measure(constraints)
        val m = measurables[1].measure(Constraints())
        val gap = 8.dp.roundToPx()
        val inlineWidth = lastLine[0].roundToInt() + gap + m.width
        val fits = inlineWidth <= constraints.maxWidth
        val width = (if (fits) maxOf(t.width, inlineWidth) else maxOf(t.width, m.width))
            .coerceAtMost(constraints.maxWidth)
        val height = if (fits) maxOf(t.height, m.height) else t.height + m.height
        return layout(width, height) {
            t.place(0, 0)
            m.place(width - m.width, height - m.height)
        }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ChatMessage,
    firstOfGroup: Boolean,
    lastOfGroup: Boolean,
    showSender: Boolean,
    sender: User?,
    reply: ChatMessage?,
    replyName: String?,
    voice: VoicePlayer.State,
    onVoice: () -> Unit,
    onImage: (File) -> Unit,
    loadImage: suspend () -> File?,
    onFile: () -> Unit,
    onLongPress: () -> Unit,
    onReplyClick: (Long) -> Unit,
    onSenderClick: () -> Unit,
    signature: String?,
    onLink: (String, String) -> Unit = { _, _ -> },
    loadPreview: (suspend (String) -> com.icqnew.app.domain.LinkPreview?)? = null,
    onVoiceSeek: ((Float) -> Unit)? = null,
    onVoiceSpeed: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onVote: ((List<Int>) -> Unit)? = null,
) {
    val mine = m.fromMe
    val primary = MaterialTheme.colorScheme.primary
    val fg = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
    val big = 18.dp
    val small = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (!mine && !firstOfGroup) small else big,
        topEnd = if (mine && !firstOfGroup) small else big,
        bottomStart = if (!mine && lastOfGroup) 4.dp else if (!mine) small else big,
        bottomEnd = if (mine && lastOfGroup) 4.dp else if (mine) small else big,
    )
    val isImage = m.kind == MessageKind.IMAGE && !m.deleted
    val hasHeader = (showSender && firstOfGroup) || (m.fwdName != null && !m.deleted) || (m.replyTo != null && !m.deleted)

    // A photo without captions is shown on its own, with rounded corners and no coloured frame.
    if (isImage && !hasHeader) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            ImageContent(m, shape, onImage, loadImage, onLongPress)
            if (m.reactions.isNotEmpty()) {
                ReactionPills(m, false, primary, onReact, Modifier.padding(top = 4.dp))
            }
        }
        return
    }

    val background = if (mine) {
        Modifier.background(Brush.linearGradient(listOf(lerp(primary, Color.White, 0.10f), primary)), shape)
    } else {
        Modifier.background(MaterialTheme.colorScheme.surface, shape)
    }
    Column(
        Modifier
            .widthIn(min = 64.dp, max = 300.dp)
            .then(if (m.replyTo != null && !m.deleted && !isImage) Modifier.width(IntrinsicSize.Max) else Modifier)
            .shadow(if (mine) 0.dp else 0.6.dp, shape, clip = false)
            .clip(shape)
            .then(background)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(if (isImage) 3.dp else 0.dp)
            .padding(horizontal = if (isImage) 0.dp else 11.dp, vertical = if (isImage) 0.dp else 5.dp),
    ) {
        val inset = if (isImage) Modifier.padding(horizontal = 8.dp, vertical = 2.dp) else Modifier
        if (showSender && firstOfGroup) {
            Box(inset) { SenderName(sender?.name, m.fromUin, sender?.verified == true, onSenderClick) }
        }
        if (m.fwdName != null && !m.deleted) {
            Text(
                "Переслано от ${m.fwdName}",
                color = if (mine) fg.copy(alpha = 0.85f) else primary, fontSize = 12.sp, fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Medium,
                modifier = inset.padding(bottom = 3.dp),
            )
        }
        if (m.replyTo != null && !m.deleted) {
            Row(
                inset
                    .then(if (isImage) Modifier else Modifier.fillMaxWidth())
                    .padding(top = 2.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (mine) Color.Black.copy(alpha = 0.12f) else primary.copy(alpha = 0.10f))
                    .clickable { onReplyClick(m.replyTo) }
                    .height(IntrinsicSize.Min),
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(if (mine) Color.White else primary))
                Column(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        replyName ?: "Сообщение", overflow = TextOverflow.Ellipsis, color = if (mine) fg else primary,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                    )
                    EmojiText(
                        reply?.let { shortPreview(it) } ?: "Сообщение недоступно",
                        color = fg.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val metaColor = fg.copy(alpha = 0.75f)
        if (m.deleted) {
            TextWithMeta("🚫 Сообщение удалено", fg.copy(alpha = 0.7f)) { Meta(m, metaColor) }
        } else when (m.kind) {
            MessageKind.IMAGE -> ImageContent(m, RoundedCornerShape(14.dp), onImage, loadImage, onLongPress)
            MessageKind.FILE -> {
                Row(
                    Modifier.combinedClickable(onClick = onFile, onLongClick = onLongPress),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(if (mine) Color.White.copy(alpha = 0.22f) else primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = if (mine) fg else primary,
                            modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(m.attName ?: "Файл", color = fg, fontWeight = FontWeight.Medium, maxLines = 2)
                        Text(FileUtils.humanSize(m.attSize), color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
                Box(Modifier.align(Alignment.End)) { Meta(m, metaColor) }
            }
            MessageKind.VOICE -> {
                val playing = voice.playingId == m.id
                val paused = playing && voice.paused
                val total = if (playing && voice.durationMs > 0) voice.durationMs.toLong() else m.attDurMs
                val progress = if (playing && voice.durationMs > 0) voice.positionMs / voice.durationMs.toFloat() else 0f
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(220.dp)) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(if (mine) Color.White else primary).clickable(onClick = onVoice),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (playing && !paused) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                            tint = if (mine) primary else Color.White,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Waveform(m.id, progress, if (mine) Color.White else primary, onSeek = onVoiceSeek)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (playing) durationText(voice.positionMs.toLong()) + " / " + durationText(total) else durationText(total),
                                color = fg.copy(alpha = 0.8f), fontSize = 12.sp,
                            )
                            if (playing) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = 0.15f))
                                        .clickable(onClick = onVoiceSpeed).padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        (if (voice.speed % 1f == 0f) voice.speed.toInt().toString() else voice.speed.toString()) + "x",
                                        color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Meta(m, metaColor)
                        }
                    }
                }
            }
            MessageKind.POLL -> {
                val p = m.poll
                if (p != null) PollContent(m, p, fg, primary, onVote) else Text(m.text, color = fg)
                Box(Modifier.align(Alignment.End)) { Meta(m, metaColor) }
            }
            else -> {
                TextWithMeta(
                    m.text, fg, rich = true,
                    linkColor = if (mine) Color.White else Color(0xFF3D9BFF),
                    onLink = onLink, onLongPress = onLongPress,
                    spoilerColor = if (mine) primary else MaterialTheme.colorScheme.surface,
                ) { Meta(m, metaColor) }
                val url = remember(m.text) { com.icqnew.app.presentation.emoji.RichFormat.firstUrl(m.text) }
                if (url != null && loadPreview != null) {
                    LinkPreviewCard(url, mine, loadPreview) { onLink(com.icqnew.app.presentation.emoji.RichFormat.TAG_URL, url) }
                }
            }
        }
        if (signature != null) {
            Text(signature, color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontStyle = FontStyle.Italic, modifier = inset)
        }
        // reactions live inside the post, bottom-left (Telegram style)
        androidx.compose.animation.AnimatedVisibility(
            visible = m.reactions.isNotEmpty() && !m.deleted,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            ReactionPills(m, mine, primary, onReact, inset.padding(top = 5.dp, bottom = 2.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReactionPills(m: ChatMessage, onColored: Boolean, primary: Color, onReact: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        m.reactions.forEach { r ->
            val bg = when {
                onColored && r.mine -> Color.White
                onColored -> Color.White.copy(alpha = 0.22f)
                r.mine -> primary
                else -> primary.copy(alpha = 0.13f)
            }
            val fgc = when {
                onColored && r.mine -> primary
                onColored -> Color.White
                r.mine -> Color.White
                else -> primary
            }
            val scale by androidx.compose.animation.core.animateFloatAsState(
                if (r.mine) 1f else 0.97f, androidx.compose.animation.core.spring(dampingRatio = 0.45f), label = "react",
            )
            Row(
                Modifier
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .clickable { onReact(r.emoji) }
                    .padding(start = 6.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmojiImage(r.emoji, 18.dp)
                Spacer(Modifier.width(4.dp))
                androidx.compose.animation.AnimatedContent(r.count, label = "rc") { c ->
                    Text(c.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fgc)
                }
            }
        }
    }
}

/** Telegram-like poll: tap an answer to vote, then animated result bars. */
@Composable
private fun PollContent(
    m: ChatMessage,
    p: com.icqnew.app.domain.Poll,
    fg: Color,
    primary: Color,
    onVote: ((List<Int>) -> Unit)?,
) {
    val accent = if (m.fromMe) Color.White else primary
    val showResults = p.voted || p.closed || onVote == null
    var picked by remember(m.id, p.my) { mutableStateOf(p.my.toSet()) }
    Column(Modifier.width(250.dp).padding(top = 2.dp, bottom = 4.dp)) {
        com.icqnew.app.presentation.emoji.RichText(m.text, color = fg, fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 2.dp))
        Text(
            (if (p.closed) "Опрос завершён" else if (p.anonymous) "Анонимный опрос" else "Публичный опрос") +
                if (p.multiple) " · несколько ответов" else "",
            color = fg.copy(alpha = 0.7f), fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        val total = p.voters.coerceAtLeast(1)
        p.options.forEachIndexed { i, o ->
            val mineOpt = i in p.my
            val share = if (showResults) o.votes / total.toFloat() else 0f
            val anim by androidx.compose.animation.core.animateFloatAsState(
                share, androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "bar",
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !showResults && onVote != null) {
                        if (p.multiple) picked = if (i in picked) picked - i else picked + i
                        else onVote?.invoke(listOf(i))
                    }
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showResults) {
                        Text(
                            "${(share * 100).roundToInt()}%", color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.width(44.dp),
                        )
                    } else {
                        val on = i in picked
                        Box(
                            Modifier.size(22.dp)
                                .clip(if (p.multiple) RoundedCornerShape(6.dp) else CircleShape)
                                .background(if (on) accent else Color.Transparent)
                                .border(2.dp, if (on) accent else fg.copy(alpha = 0.45f), if (p.multiple) RoundedCornerShape(6.dp) else CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Icon(Icons.Filled.Check, null, tint = if (m.fromMe) primary else Color.White, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(o.text, color = fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (showResults && mineOpt) {
                        Icon(Icons.Filled.CheckCircle, null, tint = accent, modifier = Modifier.size(18.dp))
                    }
                }
                if (showResults) {
                    Box(
                        Modifier.padding(start = 44.dp, top = 5.dp).fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.12f)),
                    ) {
                        Box(Modifier.fillMaxWidth(anim.coerceIn(0.015f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                            .background(accent))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(votesText(p.voters), color = fg.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            if (!showResults && p.multiple && picked.isNotEmpty()) {
                Text(
                    "Голосовать", color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onVote?.invoke(picked.sorted()) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else if (p.voted && !p.closed && onVote != null) {
                Text(
                    "Отменить голос", color = accent, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onVote(emptyList()) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

fun votesText(n: Int): String {
    val w = when {
        n % 100 in 11..14 -> "голосов"
        n % 10 == 1 -> "голос"
        n % 10 in 2..4 -> "голоса"
        else -> "голосов"
    }
    return if (n == 0) "Пока нет голосов" else "$n $w"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageContent(
    m: ChatMessage,
    shape: androidx.compose.ui.graphics.Shape,
    onImage: (File) -> Unit,
    loadImage: suspend () -> File?,
    onLongPress: () -> Unit,
) {
    var file by remember(m.id) { mutableStateOf<File?>(null) }
    // real proportions of the photo, so it is shown whole (not cut to a square)
    var ratio by remember(m.id) { mutableFloatStateOf(0.8f) }
    LaunchedEffect(m.id, m.attId, m.localPath) {
        val f = loadImage()
        if (f != null) {
            ratio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { imageRatio(f) } ?: ratio
        }
        file = f
    }
    Box(
        Modifier.width(260.dp).aspectRatio(ratio.coerceIn(0.5f, 2.4f)).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = { file?.let(onImage) }, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(
                model = file, contentDescription = null,
                // whole picture; only extremely tall / wide ones get slightly trimmed
                contentScale = if (ratio in 0.5f..2.4f) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) { Meta(m, Color.White) }
    }
}

/** Pseudo waveform for voice messages (bars are derived from the message id). */
@Composable
private fun Waveform(seed: Long, progress: Float, color: Color, onSeek: ((Float) -> Unit)? = null) {
    val bars = remember(seed) {
        val r = java.util.Random(seed)
        List(28) { 0.25f + r.nextFloat() * 0.75f }
    }
    var width by remember { mutableIntStateOf(1) }
    // tap or drag along the bars = rewind / fast-forward
    val seekMod = if (onSeek == null) Modifier else Modifier
        .pointerInput(seed) { detectTapGestures { o -> onSeek((o.x / width).coerceIn(0f, 1f)) } }
        .pointerInput(seed) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                onSeek((change.position.x / width).coerceIn(0f, 1f))
            }
        }
    Row(Modifier.fillMaxWidth().height(28.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }.then(seekMod),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        bars.forEachIndexed { i, h ->
            val played = i.toFloat() / bars.size <= progress && progress > 0f
            Box(
                Modifier.weight(1f).fillMaxHeight(h).clip(RoundedCornerShape(2.dp))
                    .background(if (played) color else color.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
fun Meta(m: ChatMessage, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (m.views > 0 && !m.deleted) { // channel post: how many people saw it
            Icon(Icons.Filled.Visibility, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(2.dp))
            Text(compactCount(m.views), color = color, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
        }
        if (m.edited && !m.deleted) {
            Text("изм.", color = color, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
        }
        Text(formatClock(m.ts), color = color, fontSize = 11.sp)
        if (m.fromMe) {
            Spacer(Modifier.width(3.dp))
            when (m.status) {
                MessageStatus.SENDING -> Icon(Icons.Filled.Schedule, null, tint = color, modifier = Modifier.size(13.dp))
                MessageStatus.SENT -> Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(14.dp))
                MessageStatus.READ -> Icon(
                    Icons.Filled.DoneAll, null, modifier = Modifier.size(16.dp),
                    tint = if (color.red > 0.9f && color.green > 0.9f) Color(0xFFB3F0FF) else MaterialTheme.colorScheme.primary,
                )
                MessageStatus.FAILED -> Icon(
                    Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** 999 / 1,2K / 12K / 1,3M — like Telegram's view counter. */
fun compactCount(n: Int): String = when {
    n < 1000 -> n.toString()
    n < 10_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1000f).replace(".0K", "K").replace('.', ',')
    n < 1_000_000 -> "${n / 1000}K"
    else -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f).replace(".0M", "M").replace('.', ',')
}

fun durationText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ---- message actions --------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    m: ChatMessage,
    canDeleteForAll: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: (() -> Unit)?,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
) {
    val sent = m.serverId != null
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (sent) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                items(QUICK_REACTIONS) { e ->
                    val mine = m.reactions.any { it.emoji == e && it.mine }
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onReact(e) },
                        contentAlignment = Alignment.Center,
                    ) { EmojiImage(e, 28.dp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        @Composable
        fun item(label: String, icon: ImageVector, tint: Color?, click: () -> Unit) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 22.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(18.dp))
                Text(label, color = tint ?: MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            }
        }
        if (sent && onReply != null) item("Ответить", Icons.AutoMirrored.Filled.Reply, null, onReply)
        if (m.kind == MessageKind.TEXT || m.kind == MessageKind.STICKER) item("Копировать", Icons.Filled.ContentCopy, null, onCopy)
        if (sent) item("Переслать", Icons.Filled.Shortcut, null, onForward)
        if (m.kind.isAttachment && m.attId != null) item("Сохранить", Icons.Filled.Download, null, onSave)
        if (m.fromMe && sent && m.kind == MessageKind.TEXT) item("Изменить", Icons.Filled.Edit, null, onEdit)
        if (m.fromMe || canDeleteForAll || !sent) item("Удалить", Icons.Filled.Delete, MaterialTheme.colorScheme.error, onDelete)
        else item("Удалить у себя", Icons.Filled.Delete, MaterialTheme.colorScheme.error, onDelete)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun ForwardDialog(targets: List<User>, onDismiss: () -> Unit, onSend: (List<Long>) -> Unit) {
    val selected = remember { mutableStateListOf<Long>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переслать в…") },
        text = {
            if (targets.isEmpty()) Text("Нет чатов")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(targets, key = { it.uin }) { u ->
                    val on = u.uin in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { if (on) selected.remove(u.uin) else selected.add(u.uin) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(u.name, u.uin, u.avatarUrl, false, 40.dp, isGroup = u.isGroup)
                        Spacer(Modifier.width(12.dp))
                        Text(u.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Checkbox(checked = on, onCheckedChange = {
                            if (on) selected.remove(u.uin) else selected.add(u.uin)
                        })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { onSend(selected.toList()) }) {
                Text(if (selected.size > 1) "Отправить (${selected.size})" else "Отправить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ImageViewer(file: File, onClose: () -> Unit, onSave: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = file, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
                IconButton(onClick = onSave) { Icon(Icons.Filled.Download, "Сохранить", tint = Color.White) }
            }
        }
    }
}

@Composable
fun ComposerBanner(icon: @Composable () -> Unit, title: String, text: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(2.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            EmojiText(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Отмена") }
    }
}

/** A call shown inside the chat (instead of a separate "Calls" tab). Tap = call back. */
@Composable
fun CallLine(c: com.icqnew.app.domain.CallRecord, onCall: (Boolean) -> Unit) {
    val missed = c.status == com.icqnew.app.domain.CallStatus.MISSED
    val title = when {
        missed -> if (c.video) "Пропущенный видеозвонок" else "Пропущенный звонок"
        c.status == com.icqnew.app.domain.CallStatus.DECLINED -> "Отклонённый звонок"
        c.status == com.icqnew.app.domain.CallStatus.CANCELLED -> "Отменённый звонок"
        c.outgoing -> if (c.video) "Исходящий видеозвонок" else "Исходящий звонок"
        else -> if (c.video) "Входящий видеозвонок" else "Входящий звонок"
    }
    val tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (c.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable { onCall(c.video) }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        c.video -> Icons.Filled.Videocam
                        missed -> Icons.AutoMirrored.Filled.CallMissed
                        c.outgoing -> Icons.AutoMirrored.Filled.CallMade
                        else -> Icons.AutoMirrored.Filled.CallReceived
                    },
                    null, tint = tint, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (missed) tint else MaterialTheme.colorScheme.onSurface)
                Text(
                    java.time.Instant.ofEpochMilli(c.ts).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) +
                        if (c.durationSec > 0) " · " + durationText(c.durationSec * 1000L) else "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(if (c.video) Icons.Filled.Videocam else Icons.Filled.Call, "Позвонить", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Card with the title / picture of the first link in a message (like Telegram). */
@Composable
fun LinkPreviewCard(url: String, mine: Boolean, load: suspend (String) -> com.icqnew.app.domain.LinkPreview?, onOpen: () -> Unit) {
    var p by remember(url) { mutableStateOf<com.icqnew.app.domain.LinkPreview?>(null) }
    LaunchedEffect(url) { p = runCatching { load(url) }.getOrNull() }
    val lp = p ?: return
    val accent = if (mine) Color.White else MaterialTheme.colorScheme.primary
    if (lp.peer != 0L) {
        // a person / group / channel inside ICQ: avatar, name, members + "open" button
        Column(
            Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (mine) Color.Black.copy(alpha = 0.14f) else accent.copy(alpha = 0.08f))
                .clickable(onClick = onOpen).padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    lp.title, lp.peer, lp.image, false, 48.dp,
                    isGroup = lp.peer < 0, isChannel = lp.type == "channel", storyRing = false,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    com.icqnew.app.presentation.components.NameLine(
                        lp.title, lp.verified, color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when (lp.type) {
                            "channel" -> "канал · ${lp.count} подписчиков"
                            "group" -> "группа · ${lp.count} участников"
                            else -> "пользователь ICQ"
                        },
                        fontSize = 13.sp, color = (if (mine) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.75f),
                    )
                }
            }
            if (lp.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    lp.description, fontSize = 13.sp, maxLines = 3,
                    color = (if (mine) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (mine) Color.White.copy(alpha = 0.2f) else accent.copy(alpha = 0.14f))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (lp.type) { "channel" -> "Открыть канал"; "group" -> "Открыть группу"; else -> "Открыть профиль" },
                    color = accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                )
            }
        }
        return
    }
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (mine) Color.Black.copy(alpha = 0.12f) else accent.copy(alpha = 0.08f))
            .clickable(onClick = onOpen).height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(8.dp)) {
            if (lp.site.isNotBlank()) Text(lp.site, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            if (lp.title.isNotBlank()) Text(lp.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2,
                color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
            if (lp.description.isNotBlank()) Text(lp.description, fontSize = 13.sp, maxLines = 3,
                color = (if (mine) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.8f))
            lp.image?.let { img ->
                Spacer(Modifier.height(6.dp))
                coil.compose.AsyncImage(
                    model = img, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}

/** Round video message: plays muted in a loop inside the circle, tap = full screen with sound. */
@Composable
fun VideoNote(m: ChatMessage, source: suspend () -> Pair<String, Map<String, String>>?, onOpen: () -> Unit, onLongPress: () -> Unit) {
    var src by remember(m.id) { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    LaunchedEffect(m.id) { src = runCatching { source() }.getOrNull() }
    Box(
        Modifier.size(220.dp).clip(CircleShape).background(Color.Black)
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(m.id) { detectTapGestures(onTap = { onOpen() }, onLongPress = { onLongPress() }) },
        contentAlignment = Alignment.Center,
    ) {
        val s = src
        if (s != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() }
                        setOnErrorListener { _, _, _ -> true }
                        setVideoURI(android.net.Uri.parse(s.first), s.second)
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text(durationText(m.attDurMs), color = Color.White, fontSize = 12.sp) }
    }
}

/** Tap on a phone number in a message: is this person in ICQ? */
@Composable
fun PhoneLookupDialog(
    number: String,
    lookup: suspend (String) -> User?,
    onWrite: (User) -> Unit,
    onProfile: (User) -> Unit,
    onCall: () -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember(number) { mutableStateOf(true) }
    var user by remember(number) { mutableStateOf<User?>(null) }
    LaunchedEffect(number) {
        user = runCatching { lookup(number) }.getOrNull()
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(number) },
        text = {
            Column {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Проверяем, есть ли номер в ICQ…")
                    }
                    user != null -> Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onProfile(user!!) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(user!!.name, user!!.uin, user!!.avatarUrl, user!!.online, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            com.icqnew.app.presentation.components.NameLine(user!!.name, user!!.verified, fontWeight = FontWeight.SemiBold)
                            Text("есть в ICQ ✔", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        }
                    }
                    else -> Text("Этого номера нет в ICQ (или человек скрыл его в настройках).")
                }
            }
        },
        confirmButton = {
            if (user != null) TextButton(onClick = { onWrite(user!!) }) { Text("Написать") }
            else TextButton(onClick = onCall) { Text("Позвонить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}


/** width / height of a picture (EXIF rotation taken into account), null if unknown. */
private fun imageRatio(f: File): Float? = try {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) null
    else {
        val rot = runCatching {
            androidx.exifinterface.media.ExifInterface(f.absolutePath).rotationDegrees
        }.getOrDefault(0)
        if (rot == 90 || rot == 270) o.outHeight.toFloat() / o.outWidth else o.outWidth.toFloat() / o.outHeight
    }
} catch (e: Exception) {
    null
}
