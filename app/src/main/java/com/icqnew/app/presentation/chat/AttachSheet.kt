package com.icqnew.app.presentation.chat

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One photo / video of the phone's gallery. */
data class GalleryItem(val uri: Uri, val video: Boolean, val durationMs: Long)

private fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasGalleryAccess(ctx: android.content.Context): Boolean = galleryPermissions().any {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

/** Newest photos and videos (MediaStore), up to [limit]. */
private fun loadGallery(ctx: android.content.Context, limit: Int = 300): List<GalleryItem> {
    val out = ArrayList<GalleryItem>()
    val uri = MediaStore.Files.getContentUri("external")
    val proj = arrayOf(
        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Video.VideoColumns.DURATION,
    )
    val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
    val args = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    runCatching {
        ctx.contentResolver.query(uri, proj, sel, args, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val durC = c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(idC)
                val video = c.getInt(typeC) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out.add(GalleryItem(ContentUris.withAppendedId(base, id), video, if (durC >= 0) c.getLong(durC) else 0L))
            }
        }
    }
    return out
}

/**
 * Our own attachment picker (Telegram-like): gallery grid with multi-select and numbered ticks,
 * plus camera / file / poll / round video buttons. Falls back to the system pickers when needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    onDismiss: () -> Unit,
    onSend: (List<Uri>) -> Unit,
    onEditPhoto: (Uri) -> Unit,
    onCamera: () -> Unit,
    onSystemGallery: () -> Unit,
    onFile: () -> Unit,
    onVideoNote: () -> Unit,
    onPoll: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var granted by remember { mutableStateOf(hasGalleryAccess(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasGalleryAccess(ctx)
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(galleryPermissions()) }
    val items by produceState(emptyList<GalleryItem>(), granted) {
        value = if (granted) withContext(Dispatchers.IO) { loadGallery(ctx) } else emptyList()
    }
    val picked = remember { mutableStateListOf<Uri>() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (picked.isEmpty()) "Галерея" else "Выбрано: ${picked.size}",
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f),
                )
                if (picked.isNotEmpty()) {
                    TextButton(onClick = { picked.clear() }) { Text("Сбросить") }
                }
            }
            Box(Modifier.weight(1f, fill = false)) {
                if (!granted) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Разрешите доступ к фото и видео, чтобы выбирать их прямо здесь", fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { ask.launch(galleryPermissions()) }) { Text("Разрешить доступ") }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 470.dp),
                    ) {
                        item(key = "camera") {
                            Box(
                                Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E2228)).clickable { onDismiss(); onCamera() },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.CameraAlt, "Камера", tint = Color.White, modifier = Modifier.size(34.dp)) }
                        }
                        items(items, key = { it.uri.toString() }) { g ->
                            val n = picked.indexOf(g.uri)
                            GalleryCell(g, if (n >= 0) n + 1 else 0,
                                onToggle = { if (n >= 0) picked.remove(g.uri) else picked.add(g.uri) },
                                onOpen = {
                                    if (picked.isEmpty() && !g.video) { onDismiss(); onEditPhoto(g.uri) }
                                    else if (n >= 0) picked.remove(g.uri) else picked.add(g.uri)
                                },
                            )
                        }
                    }
                }
            }
            // bottom: either "send N" or the attachment kinds
            AnimatedVisibility(
                visible = picked.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary)
                            .clickable { val l = picked.toList(); onDismiss(); onSend(l) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Отправить ${picked.size}", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            AnimatedVisibility(visible = picked.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AttachKind(Icons.Filled.PhotoLibrary, "Галерея", Color(0xFF3D9BFF)) { onDismiss(); onSystemGallery() }
                    AttachKind(Icons.AutoMirrored.Filled.InsertDriveFile, "Файл", Color(0xFF4CAF50)) { onDismiss(); onFile() }
                    AttachKind(Icons.Filled.Videocam, "Кружок", Color(0xFFFF7043)) { onDismiss(); onVideoNote() }
                    if (onPoll != null) AttachKind(Icons.Filled.BarChart, "Опрос", Color(0xFFFFB300)) { onDismiss(); onPoll() }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GalleryCell(g: GalleryItem, number: Int, onToggle: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val scale by animateFloatAsState(if (number > 0) 0.86f else 1f, spring(dampingRatio = 0.6f), label = "pick")
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpen)) {
            if (g.video) {
                val thumb by produceState<Bitmap?>(null, g.uri) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(g.uri, Size(320, 320), null)
                            else @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(
                                ctx.contentResolver, ContentUris.parseId(g.uri), MediaStore.Video.Thumbnails.MINI_KIND, null,
                            )
                        }.getOrNull()
                    }
                }
                thumb?.let {
                    androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Row(
                    Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 5.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Text(durationText(g.durationMs), color = Color.White, fontSize = 11.sp)
                }
            } else {
                AsyncImage(model = g.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        // numbered selection circle (top-right), like Telegram
        Box(
            Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).clip(CircleShape)
                .background(if (number > 0) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f))
                .border(1.5.dp, Color.White, CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(number > 0, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Text(number.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AttachKind(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** New poll: question, 2..10 answers, anonymous / several answers switches. */
@Composable
fun CreatePollDialog(onDismiss: () -> Unit, onCreate: (String, List<String>, Boolean, Boolean) -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var anonymous by remember { mutableStateOf(true) }
    var multiple by remember { mutableStateOf(false) }
    val clean = options.map { it.trim() }.filter { it.isNotEmpty() }
    val ok = question.isNotBlank() && clean.size >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый опрос") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(question, { question = it.take(255) }, label = { Text("Вопрос") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Варианты ответа", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                options.forEachIndexed { i, o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            o, { options[i] = it.take(100) }, singleLine = true,
                            placeholder = { Text("Вариант ${i + 1}") }, modifier = Modifier.weight(1f).padding(top = 4.dp),
                        )
                        if (options.size > 2) {
                            IconButton(onClick = { options.removeAt(i) }) { Icon(Icons.Filled.Close, "Удалить") }
                        }
                    }
                }
                if (options.size < 10) {
                    TextButton(onClick = { options.add("") }) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить вариант")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Анонимный опрос", modifier = Modifier.weight(1f))
                    Switch(anonymous, { anonymous = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Несколько ответов", modifier = Modifier.weight(1f))
                    Switch(multiple, { multiple = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(question.trim(), clean, multiple, anonymous) }, enabled = ok) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
