package com.icqnew.app.presentation.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.icqnew.app.data.local.FileUtils
import com.icqnew.app.domain.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File

private enum class ViewKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, OTHER }

private val TEXT_EXT = setOf(
    "txt", "log", "md", "json", "xml", "csv", "tsv", "ini", "cfg", "conf", "yaml", "yml", "srt", "vtt",
    "kt", "java", "py", "js", "ts", "html", "htm", "css", "c", "cpp", "h", "go", "rs", "sh", "sql", "php",
)
private const val TEXT_LIMIT = 2L * 1024 * 1024
private const val PDF_DOWNLOAD_LIMIT = 200L * 1024 * 1024
private const val IMAGE_DOWNLOAD_LIMIT = 60L * 1024 * 1024

private fun kindOf(name: String, mime: String?, size: Long): ViewKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    val mt = (mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "").lowercase()
    return when {
        mt.startsWith("image/") && size <= IMAGE_DOWNLOAD_LIMIT -> ViewKind.IMAGE
        mt.startsWith("video/") -> ViewKind.VIDEO
        mt.startsWith("audio/") -> ViewKind.AUDIO
        (mt == "application/pdf" || ext == "pdf") && size <= PDF_DOWNLOAD_LIMIT -> ViewKind.PDF
        (mt.startsWith("text/") || ext in TEXT_EXT || mt == "application/json") && size <= TEXT_LIMIT -> ViewKind.TEXT
        else -> ViewKind.OTHER
    }
}

/**
 * Built-in viewer for attachments: photos, video & audio (streamed), PDF pages, text files.
 * Anything else gets a card with "download" and "open in another app".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewer(
    m: ChatMessage,
    loadFile: suspend () -> File?,
    streamSource: suspend () -> Pair<String, Map<String, String>>?,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val name = m.attName ?: "Файл"
    val kind = remember(m.id) { kindOf(name, m.attMime, m.attSize) }
    var file by remember(m.id) { mutableStateOf<File?>(null) }
    var loading by remember(m.id) { mutableStateOf(false) }
    var failed by remember(m.id) { mutableStateOf(false) }

    suspend fun ensureFile(): File? {
        file?.let { return it }
        loading = true
        val f = loadFile()
        loading = false
        if (f == null) failed = true
        file = f
        return f
    }

    val scope = rememberCoroutineScope()
    fun openExternally() {
        if (loading) return
        scope.launch {
            val f = ensureFile()
            if (f != null) {
                try {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                    val mime = m.attMime?.takeIf { it.isNotBlank() }
                        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                        ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(intent, "Открыть с помощью"))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "Нет приложения для этого файла", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(m.id) {
        if (kind == ViewKind.IMAGE || kind == ViewKind.PDF || kind == ViewKind.TEXT) ensureFile()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть") } },
                title = {
                    Column {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text(FileUtils.humanSize(m.attSize), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                },
                actions = {
                    IconButton(onClick = onSave) { Icon(Icons.Filled.Download, "Скачать") }
                    IconButton(onClick = ::openExternally) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Открыть в приложении") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                ),
                windowInsets = WindowInsets.statusBars,
            )
            Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                val f = file
                when {
                    failed -> Text("Не удалось загрузить файл", color = Color.White)
                    kind == ViewKind.VIDEO || kind == ViewKind.AUDIO -> MediaPlayerView(kind == ViewKind.AUDIO, name, streamSource)
                    f == null && kind != ViewKind.OTHER -> CircularProgressIndicator(color = Color.White)
                    kind == ViewKind.IMAGE && f != null -> ZoomableImage(f)
                    kind == ViewKind.PDF && f != null -> PdfView(f)
                    kind == ViewKind.TEXT && f != null -> TextView(f)
                    else -> OtherFileCard(name, m.attSize, loading, onSave, ::openExternally)
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(f: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    AsyncImage(
        model = f, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInputTransform { zoom, pan ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f }
            }
            .graphicsLayerCompat(scale, ox, oy),
    )
}

private fun Modifier.pointerInputTransform(onChange: (Float, androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ -> onChange(zoom, pan) }
        }
    )

private fun Modifier.graphicsLayerCompat(scale: Float, x: Float, y: Float): Modifier =
    this.then(Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = x, translationY = y))

@Composable
private fun MediaPlayerView(audio: Boolean, name: String, source: suspend () -> Pair<String, Map<String, String>>?) {
    var src by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    var error by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { src = source(); if (src == null) error = true }
    val s = src
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (s != null) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { mp ->
                            prepared = true
                            mp.isLooping = false
                            start()
                            controller.show(0)
                        }
                        setOnErrorListener { _, _, _ -> error = true; true }
                        setVideoURI(Uri.parse(s.first), s.second)
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = if (audio) Modifier.fillMaxWidth().height(1.dp) else Modifier.fillMaxSize(),
            )
        }
        if (audio) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(140.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.MusicNote, null, tint = Color.White, modifier = Modifier.size(72.dp)) }
                Spacer(Modifier.height(16.dp))
                Text(name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
                Text("Нажмите на экран для управления", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
        if (error) Text("Не удалось воспроизвести", color = Color.White)
        else if (!prepared) CircularProgressIndicator(color = Color.White)
    }
}

private val pdfLock = Mutex()

@Composable
private fun PdfView(f: File) {
    var renderer by remember(f) { mutableStateOf<PdfRenderer?>(null) }
    var error by remember(f) { mutableStateOf(false) }
    DisposableEffect(f) {
        val pfd = runCatching { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        renderer = pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
        if (renderer == null) error = true
        onDispose {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }
    val r = renderer
    if (error || r == null) {
        Text(if (error) "Не удалось открыть PDF" else "", color = Color.White)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.coerceAtLeast(400)
        LazyColumn(
            Modifier.fillMaxSize().background(Color(0xFF2B2B2B)),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(r.pageCount) { i ->
                var bmp by remember(i) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(i) {
                    bmp = withContext(Dispatchers.IO) {
                        pdfLock.withLock {
                            runCatching {
                                r.openPage(i).use { page ->
                                    val w = widthPx
                                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    b.eraseColor(android.graphics.Color.WHITE)
                                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    b
                                }
                            }.getOrNull()
                        }
                    }
                }
                val b = bmp
                if (b != null) {
                    Image(b.asImageBitmap(), "Страница ${i + 1}", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            item {
                Text("Страниц: ${r.pageCount}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(8.dp))
            }
        }
    }
}

@Composable
private fun TextView(f: File) {
    var text by remember(f) { mutableStateOf<String?>(null) }
    LaunchedEffect(f) { text = withContext(Dispatchers.IO) { runCatching { f.readText() }.getOrDefault("") } }
    val t = text
    if (t == null) {
        CircularProgressIndicator(color = Color.White)
        return
    }
    SelectionContainer {
        Text(
            t, color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.fillMaxSize().background(Color(0xFF151515))
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}

@Composable
private fun OtherFileCard(name: String, size: Long, loading: Boolean, onSave: () -> Unit, onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Box(
            Modifier.size(110.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(FileUtils.humanSize(size), color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpen, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Загрузка…")
            } else {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Download, null)
            Spacer(Modifier.width(8.dp))
            Text("Сохранить в «Загрузки»")
        }
    }
}
