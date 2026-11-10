package com.icqnew.app.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** AVATAR: square result with a round frame. PHOTO: keeps the photo's own proportions. */
enum class EditMode { AVATAR, PHOTO }

/** A finger-drawn line; points are relative to the frame (0..1), width is a part of the frame width. */
private class DrawLine(val color: Color, val width: Float) {
    val points = mutableStateListOf<Offset>()
}

private val ASPECTS = listOf("Исходный" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
private val SIZES = listOf("Большой" to 1600, "Оригинал" to 2560, "Средний" to 1080, "Маленький" to 720)
private val PEN_COLORS = listOf(
    Color.White, Color.Black, Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00),
    Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFAF52DE),
)
private val PEN_SIZES = listOf(0.006f, 0.012f, 0.022f)

private data class Filter(val name: String, val matrix: ColorMatrix?)

private fun filters(): List<Filter> = listOf(
    Filter("Оригинал", null),
    Filter("Ч/Б", ColorMatrix().apply { setSaturation(0f) }),
    Filter("Ярко", ColorMatrix().apply { setSaturation(1.45f) }),
    Filter("Тепло", ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 12f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 0.88f, 0f, -6f, 0f, 0f, 0f, 1f, 0f))),
    Filter("Холод", ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, -6f, 0f, 1f, 0f, 0f, 2f, 0f, 0f, 1.12f, 0f, 14f, 0f, 0f, 0f, 1f, 0f))),
    Filter("Сепия", ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
    ))),
    Filter("Контраст", ColorMatrix(floatArrayOf(1.3f, 0f, 0f, 0f, -38f, 0f, 1.3f, 0f, 0f, -38f, 0f, 0f, 1.3f, 0f, -38f, 0f, 0f, 0f, 1f, 0f))),
)

/** Decodes the picked image (max 2048 px) and applies its EXIF rotation. */
fun loadEditableBitmap(context: Context, uri: Uri): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    val rotation = try {
        context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (e: Exception) {
        0f
    }
    if (bmp != null && rotation != 0f) {
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    } else bmp
} catch (e: Exception) {
    null
}

/**
 * Photo editor: move / zoom with fingers, rotate, mirror, colour filters.
 * Returns a JPEG through [onDone].
 */
@Composable
fun ImageEditorDialog(
    uri: Uri,
    mode: EditMode,
    doneLabel: String,
    onCancel: () -> Unit,
    onDone: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val b = withContext(Dispatchers.IO) { loadEditableBitmap(context, uri) }
        if (b == null) failed = true else bitmap = b
    }
    var rotation by remember { mutableIntStateOf(0) } // 0, 90, 180, 270
    var mirror by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var filterIndex by remember { mutableIntStateOf(0) }
    var aspectIndex by remember { mutableIntStateOf(0) }
    var sizeIndex by remember { mutableIntStateOf(0) }
    var drawing by remember { mutableStateOf(false) }
    var penColor by remember { mutableStateOf(PEN_COLORS[2]) }
    var penSize by remember { mutableIntStateOf(1) }
    val lines = remember { mutableStateListOf<DrawLine>() }
    var saving by remember { mutableStateOf(false) }
    val allFilters = remember { filters() }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            // top bar
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Отмена", tint = Color.White) }
                Text(
                    if (mode == EditMode.AVATAR) "Фото профиля" else "Редактирование",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                if (lines.isNotEmpty()) IconButton(onClick = { lines.removeAt(lines.lastIndex) }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Отменить линию", tint = Color.White)
                }
                IconButton(onClick = {
                    rotation = 0; mirror = false; zoom = 1f; offset = Offset.Zero; filterIndex = 0
                    aspectIndex = 0; lines.clear(); drawing = false
                }) { Icon(Icons.Filled.Restore, "Сбросить", tint = Color.White) }
            }

            val bmp = bitmap
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    failed -> Text("Не удалось открыть изображение", color = Color.White)
                    bmp == null -> CircularProgressIndicator(color = Color.White)
                    else -> {
                        val rotW = if (rotation % 180 == 0) bmp.width else bmp.height
                        val rotH = if (rotation % 180 == 0) bmp.height else bmp.width
                        val aspect = when {
                            mode == EditMode.AVATAR -> 1f
                            ASPECTS[aspectIndex].second > 0f -> ASPECTS[aspectIndex].second
                            else -> rotW.toFloat() / rotH
                        }
                        var frame by remember { mutableStateOf(IntSize.Zero) }
                        val paint = remember(filterIndex) {
                            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                                allFilters[filterIndex].matrix?.let { colorFilter = ColorMatrixColorFilter(it) }
                            }
                        }
                        fun clamp(o: Offset, fw: Float, fh: Float, k: Float): Offset {
                            val s0 = max(fw / rotW, fh / rotH)
                            val maxX = max(0f, (rotW * s0 * k - fw) / 2f)
                            val maxY = max(0f, (rotH * s0 * k - fh) / 2f)
                            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                        }
                        Canvas(
                            Modifier
                                .aspectRatio(aspect)
                                .fillMaxSize()
                                .onSizeChanged { frame = it }
                                .pointerInput(drawing, penColor, penSize) {
                                    if (!drawing) return@pointerInput
                                    val fw = { frame.width.toFloat().coerceAtLeast(1f) }
                                    val fh = { frame.height.toFloat().coerceAtLeast(1f) }
                                    detectDragGestures(
                                        onDragStart = { p ->
                                            lines.add(DrawLine(penColor, PEN_SIZES[penSize]).apply {
                                                points.add(Offset(p.x / fw(), p.y / fh()))
                                            })
                                        },
                                    ) { change, _ ->
                                        change.consume()
                                        lines.lastOrNull()?.points?.add(Offset(change.position.x / fw(), change.position.y / fh()))
                                    }
                                }
                                .pointerInput(rotation, bmp, drawing, aspectIndex) {
                                    if (drawing) return@pointerInput
                                    detectTransformGestures { _, pan, z, _ ->
                                        val fw = frame.width.toFloat().coerceAtLeast(1f)
                                        val fh = frame.height.toFloat().coerceAtLeast(1f)
                                        zoom = (zoom * z).coerceIn(1f, 6f)
                                        // offset is kept relative to the frame size (same for screen and result)
                                        val px = Offset(offset.x * fw, offset.y * fh) + pan
                                        val c = clamp(px, fw, fh, zoom)
                                        offset = Offset(c.x / fw, c.y / fh)
                                    }
                                },
                        ) {
                            val fw = size.width
                            val fh = size.height
                            val m = buildMatrix(bmp, rotation, mirror, zoom, Offset(offset.x * fw, offset.y * fh), fw, fh)
                            clipRect { drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp, m, paint) } }
                            lines.forEach { l -> drawLineOn(this, l, fw, fh) }
                            if (mode == EditMode.AVATAR) {
                                val hole = Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    addRect(Rect(Offset.Zero, size))
                                    addOval(Rect(Offset.Zero, Size(fw, fh)))
                                }
                                drawPath(hole, Color.Black.copy(alpha = 0.55f))
                                drawCircle(Color.White, radius = min(fw, fh) / 2f, style = Stroke(2.dp.toPx()))
                            } else {
                                // rule-of-thirds grid
                                val c = Color.White.copy(alpha = 0.35f)
                                for (i in 1..2) {
                                    drawLine(c, Offset(fw * i / 3f, 0f), Offset(fw * i / 3f, fh), 1f)
                                    drawLine(c, Offset(0f, fh * i / 3f), Offset(fw, fh * i / 3f), 1f)
                                }
                                drawRect(Color.White, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }

            // filters
            val bmpForThumb = bitmap
            if (drawing) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PEN_COLORS.forEach { col ->
                        Box(
                            Modifier.size(if (col == penColor) 32.dp else 26.dp).clip(CircleShape).background(col)
                                .border(2.dp, if (col == penColor) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f), CircleShape)
                                .clickable { penColor = col },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                            .clickable { penSize = (penSize + 1) % PEN_SIZES.size },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size((6 + penSize * 6).dp).clip(CircleShape).background(penColor)) }
                }
            } else if (bmpForThumb != null) {
                val thumb = remember(bmpForThumb) {
                    val k = 120f / max(bmpForThumb.width, bmpForThumb.height)
                    Bitmap.createScaledBitmap(
                        bmpForThumb, max(1, (bmpForThumb.width * k).toInt()), max(1, (bmpForThumb.height * k).toInt()), true,
                    )
                }
                LazyRow(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(allFilters) { i, f ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { filterIndex = i }) {
                            Canvas(
                                Modifier.size(58.dp).clip(RoundedCornerShape(12.dp))
                                    .border(
                                        2.dp,
                                        if (filterIndex == i) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        RoundedCornerShape(12.dp),
                                    ),
                            ) {
                                val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                                    f.matrix?.let { colorFilter = ColorMatrixColorFilter(it) }
                                }
                                val ab = thumb
                                val s = max(size.width / ab.width, size.height / ab.height)
                                val mx = Matrix().apply {
                                    postScale(s, s)
                                    postTranslate((size.width - ab.width * s) / 2f, (size.height - ab.height * s) / 2f)
                                }
                                drawIntoCanvas { it.nativeCanvas.drawBitmap(ab, mx, p) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                f.name, fontSize = 11.sp,
                                color = if (filterIndex == i) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // tools
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tool(Icons.AutoMirrored.Filled.RotateLeft, "Влево") {
                    rotation = (rotation + 270) % 360; zoom = 1f; offset = Offset.Zero
                }
                Tool(Icons.AutoMirrored.Filled.RotateRight, "Вправо") {
                    rotation = (rotation + 90) % 360; zoom = 1f; offset = Offset.Zero
                }
                Tool(Icons.Filled.Flip, "Отразить") { mirror = !mirror }
                if (mode == EditMode.PHOTO) {
                    Tool(Icons.Filled.Crop, ASPECTS[aspectIndex].first) {
                        aspectIndex = (aspectIndex + 1) % ASPECTS.size; zoom = 1f; offset = Offset.Zero
                    }
                    Tool(Icons.Filled.Brush, if (drawing) "Готово" else "Рисовать", active = drawing) { drawing = !drawing }
                    Tool(Icons.Filled.PhotoSizeSelectLarge, SIZES[sizeIndex].first) { sizeIndex = (sizeIndex + 1) % SIZES.size }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = bitmap != null && !saving) {
                            val b = bitmap ?: return@clickable
                            saving = true
                            scope.launch {
                                val bytes = withContext(Dispatchers.Default) {
                                    render(b, mode, rotation, mirror, zoom, offset, allFilters[filterIndex].matrix,
                                        ASPECTS[aspectIndex].second, SIZES[sizeIndex].second, lines.toList())
                                }
                                saving = false
                                onDone(bytes)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Check, doneLabel, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
private fun Tool(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 6.dp),
    ) {
        Icon(icon, label, tint = tint)
        Text(label, color = if (active) tint else Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1)
    }
}

private fun drawLineOn(scope: androidx.compose.ui.graphics.drawscope.DrawScope, l: DrawLine, fw: Float, fh: Float) {
    val pts = l.points
    if (pts.isEmpty()) return
    val w = l.width * fw
    if (pts.size == 1) {
        scope.drawCircle(l.color, w / 2f, Offset(pts[0].x * fw, pts[0].y * fh))
        return
    }
    val path = Path().apply {
        moveTo(pts[0].x * fw, pts[0].y * fh)
        for (i in 1 until pts.size) lineTo(pts[i].x * fw, pts[i].y * fh)
    }
    scope.drawPath(path, l.color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Matrix that maps the source bitmap into a frame of fw x fh (same maths for the screen and the result). */
private fun buildMatrix(
    bmp: Bitmap, rotation: Int, mirror: Boolean, zoom: Float, offsetPx: Offset, fw: Float, fh: Float,
): Matrix {
    val rotW = if (rotation % 180 == 0) bmp.width else bmp.height
    val rotH = if (rotation % 180 == 0) bmp.height else bmp.width
    val s0 = max(fw / rotW, fh / rotH)
    val s = s0 * zoom
    return Matrix().apply {
        postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        if (mirror) postScale(-1f, 1f)
        postRotate(rotation.toFloat())
        postScale(s, s)
        postTranslate(fw / 2f + offsetPx.x, fh / 2f + offsetPx.y)
    }
}

private fun render(
    bmp: Bitmap, mode: EditMode, rotation: Int, mirror: Boolean, zoom: Float, offset: Offset, filter: ColorMatrix?,
    aspect: Float = 0f, maxSide: Int = 1600, lines: List<DrawLine> = emptyList(),
): ByteArray {
    val rotW = if (rotation % 180 == 0) bmp.width else bmp.height
    val rotH = if (rotation % 180 == 0) bmp.height else bmp.width
    val (outW, outH) = if (mode == EditMode.AVATAR) {
        640 to 640
    } else {
        val ratio = if (aspect > 0f) aspect else rotW.toFloat() / rotH
        val limit = min(maxSide, max(rotW, rotH)).toFloat() // never enlarged above the source
        val w = if (ratio >= 1f) limit else limit * ratio
        val h = if (ratio >= 1f) limit / ratio else limit
        w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    val m = buildMatrix(bmp, rotation, mirror, zoom, Offset(offset.x * outW, offset.y * outH), outW.toFloat(), outH.toFloat())
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        filter?.let { colorFilter = ColorMatrixColorFilter(it) }
    }
    canvas.drawBitmap(bmp, m, paint)
    if (lines.isNotEmpty()) {
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for (l in lines) {
            val pts = l.points.toList()
            if (pts.isEmpty()) continue
            pen.color = l.color.toArgb()
            pen.strokeWidth = l.width * outW
            if (pts.size == 1) {
                pen.style = Paint.Style.FILL
                canvas.drawCircle(pts[0].x * outW, pts[0].y * outH, pen.strokeWidth / 2f, pen)
                pen.style = Paint.Style.STROKE
                continue
            }
            val path = android.graphics.Path().apply {
                moveTo(pts[0].x * outW, pts[0].y * outH)
                for (i in 1 until pts.size) lineTo(pts[i].x * outW, pts[i].y * outH)
            }
            canvas.drawPath(path, pen)
        }
    }
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, if (mode == EditMode.AVATAR) 88 else 80, bos)
    out.recycle()
    return bos.toByteArray()
}
