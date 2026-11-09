package com.icqnew.app.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.icqnew.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Chat backgrounds: "" = ICQ pattern, "plain", "color:<argb>", "grad:<n>", "image:<path>". */
val WALL_COLORS: List<Color> = listOf(
    Color(0xFFE8F5E9), Color(0xFFE3F2FD), Color(0xFFFCE4EC), Color(0xFFFFF3E0), Color(0xFFEDE7F6),
    Color(0xFF1B2A22), Color(0xFF13202E), Color(0xFF2A1A24), Color(0xFF1E1E1E), Color(0xFF0B3D2E),
)

val WALL_GRADIENTS: List<List<Color>> = listOf(
    listOf(Color(0xFF00C9A7), Color(0xFF00CB66), Color(0xFF92FE9D)),
    listOf(Color(0xFF4FACFE), Color(0xFF00F2FE)),
    listOf(Color(0xFFFA709A), Color(0xFFFEE140)),
    listOf(Color(0xFFA18CD1), Color(0xFFFBC2EB)),
    listOf(Color(0xFF30CFD0), Color(0xFF330867)),
    listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
    listOf(Color(0xFFFF9A9E), Color(0xFFFECFEF)),
    listOf(Color(0xFF232526), Color(0xFF414345)),
)

@Composable
fun WallpaperBackground(spec: String, modifier: Modifier = Modifier) {
    val base = lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary, 0.05f)
    when {
        spec.startsWith("image:") -> Box(modifier) {
            AsyncImage(
                model = File(spec.removePrefix("image:")), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            if (MaterialTheme.colorScheme.background.red < 0.5f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            }
        }
        spec.startsWith("color:") -> Box(
            modifier.background(Color(spec.removePrefix("color:").toLongOrNull()?.toInt() ?: base.toArgb()))
        )
        spec.startsWith("grad:") -> {
            val g = WALL_GRADIENTS.getOrNull(spec.removePrefix("grad:").toIntOrNull() ?: 0) ?: WALL_GRADIENTS[0]
            Box(modifier.background(Brush.linearGradient(g)))
        }
        spec == "plain" -> Box(modifier.background(base))
        else -> Box(modifier.background(base).icqPattern())
    }
}

/** Faint pattern of ICQ flowers. */
@Composable
fun Modifier.icqPattern(): Modifier {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    val painter = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_launcher_foreground))
    return this.drawBehind {
        val step = 120.dp.toPx()
        val s = 52.dp.toPx()
        var row = 0
        var y = -s / 2
        while (y < size.height + s) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < size.width + s) {
                translate(x - s / 2, y - s / 2) {
                    rotate((row * 23f + x / 7f) % 360f, pivot = Offset(s / 2, s / 2)) {
                        with(painter) { draw(Size(s, s), colorFilter = ColorFilter.tint(tint)) }
                    }
                }
                x += step
            }
            y += step * 0.75f
            row++
        }
    }
}

/** Copies a picked picture (max 1600 px) into the app folder and returns its path. */
private fun saveWallpaper(context: Context, uri: Uri): String? {
    val bmp = loadEditableBitmap(context, uri) ?: return null
    val k = minOf(1f, 1600f / max(bmp.width, bmp.height))
    val scaled = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
    val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
    val f = File(dir, "w_" + System.currentTimeMillis() + ".jpg")
    f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    return f.absolutePath
}

@Composable
fun WallpaperPicker(current: String, allowReset: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val p = withContext(Dispatchers.IO) { saveWallpaper(context, uri) }
            if (p != null) onPick("image:$p")
        }
    }
    val options = buildList {
        add("pattern")
        add("plain")
        WALL_GRADIENTS.indices.forEach { add("grad:$it") }
        WALL_COLORS.forEach { add("color:" + it.toArgb().toLong()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Фон чата") },
        text = {
            Column {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.heightIn(max = 380.dp)) {
                    item {
                        Tile(selected = false, onClick = { picker.launch("image/*") }) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Photo, "Из галереи", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    items(options) { spec ->
                        Tile(selected = spec == current, onClick = { onPick(spec) }) {
                            WallpaperBackground(spec, Modifier.fillMaxSize())
                        }
                    }
                }
                Text(
                    "Первый — узор ICQ, второй — без узора. 🖼 — своё фото.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = if (allowReset) ({ TextButton(onClick = { onPick("") }) { Text("Как во всех чатах") } }) else null,
    )
}

@Composable
private fun Tile(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.padding(4.dp).aspectRatio(0.62f).clip(RoundedCornerShape(12.dp))
            .border(if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (selected) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}
