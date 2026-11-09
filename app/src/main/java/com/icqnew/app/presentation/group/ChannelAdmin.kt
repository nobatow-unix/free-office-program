package com.icqnew.app.presentation.group

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.icqnew.app.domain.GroupLogEntry
import com.icqnew.app.domain.GroupStats
import com.icqnew.app.presentation.chat.compactCount
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.formatClock
import com.icqnew.app.presentation.components.formatDay
import com.icqnew.app.presentation.emoji.EmojiImage
import com.icqnew.app.presentation.emoji.EmojiText

private val GreenUp = Color(0xFF34C759)
private val RedDown = Color(0xFFFF453A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreen(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            content = content,
        )
    }
}

// ============================================================ statistics

@Composable
fun ChannelStatsScreen(isChannel: Boolean, load: suspend (Int) -> GroupStats, onBack: () -> Unit) {
    var days by remember { mutableIntStateOf(14) }
    var stats by remember { mutableStateOf<GroupStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(days) {
        error = null
        runCatching { load(days) }.onSuccess { stats = it }.onFailure { error = it.message ?: "Не удалось загрузить" }
    }
    FullScreen("Статистика", onBack) { pad ->
        val s = stats
        if (s == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error) else CircularProgressIndicator()
            }
            return@FullScreen
        }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(7 to "7 дней", 14 to "14 дней", 30 to "30 дней").forEach { (d, l) ->
                    FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) })
                }
            }
            // overview
            Card("Обзор") {
                Row(Modifier.fillMaxWidth()) {
                    Metric(if (isChannel) "Подписчики" else "Участники", compactCount(s.members),
                        delta = s.joined - s.left, modifier = Modifier.weight(1f))
                    Metric("Средний охват", compactCount(s.avgViews), modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Metric("Просмотры", compactCount(s.views), modifier = Modifier.weight(1f))
                    Metric("Реакции", compactCount(s.reactions), modifier = Modifier.weight(1f))
                }
            }
            Card(if (isChannel) "Подписки и отписки" else "Пришли и ушли") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(GreenUp); Text(" +${s.joined} пришли", fontSize = 13.sp)
                    Spacer(Modifier.width(14.dp))
                    Dot(RedDown); Text(" −${s.left} ушли", fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                GrowthChart(s, Modifier.fillMaxWidth().height(150.dp))
                DayAxis(s)
            }
            Card("Просмотры по дням") {
                ViewsChart(s, Modifier.fillMaxWidth().height(150.dp))
                DayAxis(s)
            }
            if (s.topReactions.isNotEmpty()) {
                Card("Реакции") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.topReactions.forEach { (e, n) ->
                            Row(
                                Modifier.clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                EmojiImage(e, 22.dp); Spacer(Modifier.width(5.dp))
                                Text(compactCount(n), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (s.posts.isNotEmpty()) {
                Card("Последние публикации") {
                    s.posts.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                EmojiText(
                                    com.icqnew.app.presentation.emoji.RichFormat.plain(p.text).ifBlank {
                                        when (p.kind) { "image" -> "📷 Фото"; "poll" -> "📊 Опрос"; "voice" -> "🎤 Голосовое"; else -> "📎 Вложение" }
                                    },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                                )
                                Text(formatDay(p.ts) + ", " + formatClock(p.ts), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Visibility, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(" " + compactCount(p.views), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                if (p.reactions > 0) Text("❤ ${p.reactions}", fontSize = 12.sp, color = RedDown)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Text(
        title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp),
    )
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(16.dp),
    ) { content() }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, delta: Int? = null) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedContent(value, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "metric") { v ->
                Text(v, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            if (delta != null && delta != 0) {
                Text(
                    (if (delta > 0) " +" else " −") + kotlin.math.abs(delta),
                    color = if (delta > 0) GreenUp else RedDown, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(9.dp).clip(CircleShape).background(c))

@Composable
private fun DayAxis(s: GroupStats) {
    if (s.series.isEmpty()) return
    val f = java.text.SimpleDateFormat("d MMM", java.util.Locale("ru"))
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(f.format(java.util.Date(s.series.first().day)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(f.format(java.util.Date(s.series.last().day)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Joined (up, green) and left (down, red) bars per day; grows in when shown. */
@Composable
private fun GrowthChart(s: GroupStats, modifier: Modifier) {
    val grow = remember(s) { Animatable(0f) }
    LaunchedEffect(s) { grow.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val n = s.series.size.coerceAtLeast(1)
        val max = s.series.maxOfOrNull { maxOf(it.joined, it.left) }?.coerceAtLeast(1) ?: 1
        val mid = size.height / 2
        drawLine(grid, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val slot = size.width / n
        val bw = (slot * 0.55f).coerceAtMost(18.dp.toPx())
        s.series.forEachIndexed { i, d ->
            val x = i * slot + (slot - bw) / 2
            val up = mid * 0.92f * d.joined / max * grow.value
            val dn = mid * 0.92f * d.left / max * grow.value
            if (up > 0) drawRoundRect(GreenUp, Offset(x, mid - up), Size(bw, up), CornerRadius(3.dp.toPx()))
            if (dn > 0) drawRoundRect(RedDown, Offset(x, mid), Size(bw, dn), CornerRadius(3.dp.toPx()))
        }
    }
}

/** Smooth area line of views per day; drawn from left to right. */
@Composable
private fun ViewsChart(s: GroupStats, modifier: Modifier) {
    val draw = remember(s) { Animatable(0f) }
    LaunchedEffect(s) { draw.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelMax = s.series.maxOfOrNull { it.views } ?: 0
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val pts = s.series
            if (pts.isEmpty()) return@Canvas
            val max = (pts.maxOf { it.views }).coerceAtLeast(1)
            for (k in 1..3) {
                val y = size.height * k / 4
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 0.7.dp.toPx())
            }
            val step = if (pts.size > 1) size.width / (pts.size - 1) else size.width
            fun p(i: Int) = Offset(i * step, size.height - size.height * 0.9f * pts[i].views / max)
            val path = Path().apply {
                moveTo(p(0).x, p(0).y)
                for (i in 1 until pts.size) {
                    val a = p(i - 1); val b = p(i)
                    val cx = (a.x + b.x) / 2
                    cubicTo(cx, a.y, cx, b.y, b.x, b.y)
                }
            }
            val area = Path().apply {
                addPath(path)
                lineTo(p(pts.size - 1).x, size.height); lineTo(0f, size.height); close()
            }
            val w = size.width * draw.value
            clipRect(right = w) {
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text(compactCount(labelMax), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart))
    }
}

// ============================================================ recent actions

@Composable
fun RecentActionsScreen(isChannel: Boolean, load: suspend () -> List<GroupLogEntry>, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<GroupLogEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableIntStateOf(0) } // 0 all, 1 joined, 2 left
    LaunchedEffect(Unit) {
        runCatching { load() }.onSuccess { items = it }.onFailure { error = it.message ?: "Не удалось загрузить" }
    }
    FullScreen("Недавние действия", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Все", if (isChannel) "Подписались" else "Вступили", if (isChannel) "Отписались" else "Вышли")
                    .forEachIndexed { i, l -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(l) }) }
            }
            val all = items
            when {
                all == null && error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val shown = all.filter {
                        when (filter) {
                            1 -> it.action == "join" || it.action == "added"
                            2 -> it.action == "leave" || it.action == "kick" || it.action == "ban"
                            else -> true
                        }
                    }
                    if (shown.isEmpty()) {
                        Text(
                            "Пока ничего не произошло", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(28.dp),
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        var lastDay = ""
                        val withHeaders = ArrayList<Any>()
                        shown.forEach { e ->
                            val d = formatDay(e.ts)
                            if (d != lastDay) { withHeaders.add(d); lastDay = d }
                            withHeaders.add(e)
                        }
                        items(withHeaders) { x ->
                            if (x is String) {
                                Text(
                                    x, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                                )
                            } else LogRow(x as GroupLogEntry, isChannel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(e: GroupLogEntry, isChannel: Boolean) {
    val (icon, color, text) = logText(e, isChannel)
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(e.user.name, e.user.uin, e.user.avatarUrl, false, 44.dp)
            Box(
                Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(color),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            EmojiText(e.user.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Text(formatClock(e.ts), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun logText(e: GroupLogEntry, ch: Boolean): Triple<ImageVector, Color, String> {
    val by = e.actor?.name
    return when (e.action) {
        "join" -> Triple(Icons.Filled.PersonAdd, GreenUp, if (ch) "подписался(-ась) на канал" else "вступил(а) в группу")
        "added" -> Triple(Icons.Filled.PersonAdd, GreenUp, "добавлен(а)" + (by?.let { " — $it" } ?: ""))
        "leave" -> Triple(Icons.Filled.PersonRemove, RedDown, if (ch) "отписался(-ась) от канала" else "вышел(-ла) из группы")
        "kick" -> Triple(Icons.Filled.PersonRemove, RedDown, "удалён(а)" + (by?.let { " — $it" } ?: ""))
        "ban" -> Triple(Icons.Filled.Block, RedDown, "заблокирован(а)" + (by?.let { " — $it" } ?: ""))
        else -> Triple(Icons.Filled.PersonAdd, Color.Gray, e.action)
    }
}
