package com.icqnew.app.presentation.profile

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.icqnew.app.data.remote.UserGiftDto
import com.icqnew.app.domain.Story
import com.icqnew.app.domain.StoryGroup
import com.icqnew.app.domain.User
import com.icqnew.app.presentation.appContainer
import com.icqnew.app.presentation.stories.StoryViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Profile tabs like Telegram: «Публикации | Подарки».
 * Публикации has its own tabs: Картинки | Видео | Репост (a 3-column grid, all time).
 * Lives inside a scrolling column, so the grid is drawn with plain rows.
 */
@Composable
fun ProfileMediaTabs(
    user: User,
    mine: Boolean,
    gifts: List<UserGiftDto>,
    onAdd: (() -> Unit)? = null,
    onGiftsClick: (() -> Unit)? = null,
) {
    val container = LocalContext.current.appContainer()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableIntStateOf(0) }
    val kinds = listOf("image" to "Картинки", "video" to "Видео", "repost" to "Репост")
    var reload by remember { mutableIntStateOf(0) }
    val stories = remember { mutableStateMapOf<String, List<Story>>() }
    val k = kinds[kind].first
    LaunchedEffect(user.uin, k, reload) {
        runCatching { container.chatRepository.publications(user.uin, k) }.onSuccess { stories[k] = it }
    }
    var open by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Публикации", fontWeight = FontWeight.SemiBold) })
            Tab(tab == 1, { tab = 1 }, text = { Text("Подарки" + if (gifts.isNotEmpty()) " ${gifts.size}" else "", fontWeight = FontWeight.SemiBold) })
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 0) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEachIndexed { i, (_, label) ->
                    FilterChip(selected = kind == i, onClick = { kind = i }, label = { Text(label) })
                }
                Spacer(Modifier.weight(1f))
                if (mine && onAdd != null) {
                    FilledTonalIconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Add, "Добавить") }
                }
            }
            Spacer(Modifier.height(8.dp))
            val list = stories[k]
            when {
                list == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
                list.isEmpty() -> Text(
                    when (k) {
                        "video" -> "Пока нет видео"
                        "repost" -> "Пока нет репостов"
                        else -> "Пока нет картинок"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                )
                else -> Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    list.chunked(3).forEachIndexed { row, items ->
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            items.forEachIndexed { col, st ->
                                Box(
                                    Modifier.weight(1f).aspectRatio(0.8f).clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable { open = row * 3 + col },
                                ) {
                                    PublicationThumb(st)
                                    if (st.isVideo) Icon(
                                        Icons.Filled.PlayArrow, null, tint = Color.White,
                                        modifier = Modifier.align(Alignment.Center).size(34.dp),
                                    )
                                    if (st.repostOf != null) Icon(
                                        Icons.Filled.Repeat, null, tint = Color.White,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                                    )
                                    Text(
                                        "👁 ${st.views}", color = Color.White, fontSize = 11.sp,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                    )
                                }
                            }
                            repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        } else {
            if (gifts.isEmpty()) {
                Text(
                    if (mine) "Подарков пока нет" else "У пользователя пока нет подарков",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                )
            } else {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gifts.chunked(3).forEach { items ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items.forEach { g ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .then(if (onGiftsClick != null) Modifier.clickable(onClick = onGiftsClick) else Modifier)
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    com.icqnew.app.presentation.emoji.EmojiImage(g.gift.emoji, 42.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(g.gift.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    g.from?.let {
                                        Text("от " + it.name, fontSize = 11.sp, maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    open?.let { start ->
        val list = stories[k].orEmpty()
        if (list.isNotEmpty()) {
            // the viewer goes through one "group" per publication, starting from the tapped one
            val groups = remember(list) { list.map { StoryGroup(user, listOf(it.copy(seen = true)), true) } }
            StoryViewer(
                groups = groups, start = start.coerceIn(0, groups.lastIndex),
                myUin = if (mine) user.uin else -1L,
                onClose = { open = null; reload++ },
            )
        }
    }
}

@Composable
private fun PublicationThumb(st: Story) {
    val container = LocalContext.current.appContainer()
    if (!st.isVideo) {
        var file by remember(st.id) { mutableStateOf<java.io.File?>(null) }
        LaunchedEffect(st.id) { file = runCatching { container.mediaCache.file(st.attId, "story_${st.id}.jpg") }.getOrNull() }
        file?.let {
            coil.compose.AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    } else {
        var frame by remember(st.id) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(st.id) {
            val f = runCatching { container.mediaCache.file(st.attId, "story_${st.id}.mp4") }.getOrNull() ?: return@LaunchedEffect
            frame = withContext(Dispatchers.IO) {
                runCatching {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(f.absolutePath)
                        r.getFrameAtTime(500_000)?.let { b ->
                            val k = 360f / maxOf(b.width, b.height)
                            if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true) else b
                        }
                    } finally {
                        r.release()
                    }
                }.getOrNull()
            }
        }
        frame?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
