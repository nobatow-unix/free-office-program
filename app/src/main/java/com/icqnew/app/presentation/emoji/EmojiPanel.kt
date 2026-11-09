package com.icqnew.app.presentation.emoji

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Recently used emoji (kept on the phone). */
object RecentEmoji {
    private const val KEY = "recent"
    private const val MAX = 32

    fun get(context: Context): List<String> =
        context.getSharedPreferences("emoji", Context.MODE_PRIVATE).getString(KEY, "")!!
            .split('\u0001').filter { it.isNotEmpty() }

    fun add(context: Context, glyph: String) {
        val list = (listOf(glyph) + get(context).filter { it != glyph }).take(MAX)
        context.getSharedPreferences("emoji", Context.MODE_PRIVATE).edit()
            .putString(KEY, list.joinToString("\u0001")).apply()
    }
}

/** Big emoji that are sent as stickers. */
val STICKERS: List<String> = (
    "😂 🤣 😍 🥰 😘 😎 🤩 🥳 😭 😡 😱 🤯 🤔 😴 🥺 🤗 🙄 😏 🤪 😇 🤡 💩 👻 💀 👽 🤖 🙈 🙉 🙊 😻 " +
        "🐶 🐱 🦊 🐼 🦄 🐸 🐵 🐧 🐙 👍 👎 👏 🙌 🙏 💪 🤝 ✌️ 🤞 🤟 👌 👋 💋 ❤️ 💔 💕 🔥 ✨ 💯 🎉 🎂 🎁 🏆 " +
        "🍕 🍔 🍺 ☕ 🌹 🌈 ⭐ 🚀 💣"
    ).split(' ').filter { it.isNotBlank() }

/**
 * Emoji / sticker keyboard. It has the same height as the phone keyboard, so switching
 * between the two does not make the chat jump.
 */
@Composable
fun EmojiPanel(
    height: Dp,
    onEmoji: (String) -> Unit,
    onSticker: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val context = LocalContext.current
    val categories = Emoji.categories
    // tab: 0 = recent, 1 = stickers, 2.. = categories
    var tab by remember { mutableIntStateOf(if (RecentEmoji.get(context).isEmpty()) 2 else 0) }
    var recent by remember { mutableStateOf(RecentEmoji.get(context)) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    fun pick(e: String) {
        RecentEmoji.add(context, e)
        onEmoji(e)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    TabButton(selected = tab == 0, onClick = { recent = RecentEmoji.get(context); tab = 0 }) {
                        Icon(Icons.Filled.AccessTime, "Недавние", Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    TabButton(selected = tab == 1, onClick = { tab = 1 }) {
                        Text("Стикеры", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
                item {
                    // country flags (drawn by the phone's emoji font)
                    TabButton(selected = tab == FLAGS_TAB, onClick = { tab = FLAGS_TAB }) {
                        EmojiImage("🇹🇲", 24.dp)
                    }
                }
                itemsIndexed(categories) { i, cat ->
                    TabButton(selected = tab == i + 2, onClick = {
                        tab = i + 2
                        scope.launch { grid.scrollToItem(0) }
                    }) {
                        EmojiImage(cat.items.firstOrNull()?.glyph ?: "😀", 24.dp)
                    }
                }
            }
            IconButton(onClick = onBackspace) {
                Icon(Icons.AutoMirrored.Filled.Backspace, "Стереть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (tab) {
            0 -> {
                if (recent.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Здесь появятся недавние смайлы", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.fillMaxSize()) {
                        items(recent) { e -> Cell(e, 30.dp) { pick(e) } }
                    }
                }
            }
            1 -> LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxSize()) {
                items(STICKERS) { e -> Cell(e, 64.dp) { onSticker(e) } }
            }
            FLAGS_TAB -> LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.fillMaxSize()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Флаги", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(FLAGS) { f ->
                    Box(
                        Modifier.aspectRatio(1f).clip(CircleShape).clickable { pick(f) },
                        contentAlignment = Alignment.Center,
                    ) { EmojiImage(f, 30.dp) }
                }
            }
            else -> {
                val cat = categories.getOrNull(tab - 2)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8), state = grid, modifier = Modifier.fillMaxSize(),
                ) {
                    if (cat != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                cat.title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(cat.items, key = { it.key }) { e -> Cell(e.glyph, 30.dp) { pick(e.glyph) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabButton(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Cell(glyph: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { EmojiImage(glyph, size) }
}


private const val FLAGS_TAB = 1000

/** All country flags: Turkmenistan and neighbours first, then the rest. */
private val FLAGS: List<String> by lazy {
    val first = listOf("🏳️", "🏴", "🏁")
    first + com.icqnew.app.presentation.auth.COUNTRIES.map { it.flag }.distinct()
}
