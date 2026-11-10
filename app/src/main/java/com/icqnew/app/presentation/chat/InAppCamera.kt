package com.icqnew.app.presentation.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * The app's own camera (no external camera app): photo or video, front / back,
 * flash (off / auto / on), pinch to zoom, tap to focus.
 * A photo goes on to the editor, a video straight to the send sheet.
 */
@SuppressLint("MissingPermission")
@Composable
fun InAppCamera(onDismiss: () -> Unit, onPhoto: (File) -> Unit, onVideo: (File) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build(),
        )
    }
    var video by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var busy by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var cancelled by remember { mutableStateOf(false) }

    LaunchedEffect(front, video) {
        val p = provider ?: suspendCancellableCoroutine { cont ->
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(ctx))
        }.also { provider = it }
        try {
            p.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            camera = p.bindToLifecycle(lifecycle, selector, preview, if (video) videoCapture else imageCapture)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Камера недоступна", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    LaunchedEffect(flash) { imageCapture.flashMode = flash }
    // in video mode "flash on" = torch
    LaunchedEffect(flash, video, camera, front) {
        runCatching { camera?.cameraControl?.enableTorch(video && !front && flash == ImageCapture.FLASH_MODE_ON) }
    }
    LaunchedEffect(recording) {
        while (recording != null) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(200)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (recording != null) cancelled = true
            recording?.close()
            provider?.unbindAll()
        }
    }

    fun outFile(ext: String): File {
        val dir = File(ctx.filesDir, "outbox").apply { mkdirs() }
        return File(dir, "camera_" + System.currentTimeMillis() + "." + ext)
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        val file = outFile("jpg")
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = front }
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    onPhoto(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    file.delete()
                    Toast.makeText(ctx, "Не удалось сделать фото", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    fun startVideo() {
        val file = outFile("mp4")
        cancelled = false
        startedAt = System.currentTimeMillis()
        elapsed = 0
        var pending = videoCapture.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(ctx)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                val dur = ev.recordingStats.recordedDurationNanos / 1_000_000
                when {
                    cancelled -> file.delete()
                    ev.hasError() && file.length() == 0L || dur < 500 -> {
                        file.delete()
                        Toast.makeText(ctx, "Слишком коротко", Toast.LENGTH_SHORT).show()
                    }
                    else -> onVideo(file)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { cancelled = true; recording?.stop(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                { previewView },
                Modifier.fillMaxSize()
                    .pointerInput(camera) {
                        detectTransformGestures { _, _, z, _ ->
                            val cam = camera ?: return@detectTransformGestures
                            val cur = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            runCatching { cam.cameraControl.setZoomRatio(cur * z) }
                        }
                    }
                    .pointerInput(camera) {
                        detectTapGestures { p ->
                            val cam = camera ?: return@detectTapGestures
                            val point = previewView.meteringPointFactory.createPoint(p.x, p.y)
                            runCatching { cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build()) }
                        }
                    },
            )
            // top: close + flash
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CamButton(Icons.Filled.Close, "Закрыть") { cancelled = true; recording?.stop(); onDismiss() }
                Spacer(Modifier.weight(1f))
                if (recording != null) {
                    val sec = elapsed / 1000
                    Text(
                        "● %d:%02d".format(sec / 60, sec % 60), color = Color.White, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xCCFF3B30))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                }
                CamButton(
                    when (flash) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
                        else -> Icons.Filled.FlashOff
                    },
                    "Вспышка",
                ) {
                    flash = when (flash) {
                        ImageCapture.FLASH_MODE_OFF -> if (video) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }
            }
            // bottom: mode switch + shutter + flip
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f))
                    .navigationBarsPadding().padding(top = 10.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (recording == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        ModeLabel("ФОТО", !video) { video = false }
                        ModeLabel("ВИДЕО", video) { video = true; if (flash == ImageCapture.FLASH_MODE_AUTO) flash = ImageCapture.FLASH_MODE_OFF }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(52.dp))
                    val rec = recording != null
                    val inner by animateDpAsState(if (rec) 30.dp else 60.dp, label = "shutter")
                    Box(
                        Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                            .clickable {
                                when {
                                    !video -> takePhoto()
                                    rec -> recording?.stop()
                                    else -> startVideo()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(inner).clip(if (rec) RoundedCornerShape(8.dp) else CircleShape)
                                .background(if (video) Color(0xFFFF3B30) else Color.White.copy(alpha = if (busy) 0.5f else 1f)),
                        )
                    }
                    if (!rec) CamButton(Icons.Filled.Cameraswitch, "Сменить камеру") { front = !front }
                    else Spacer(Modifier.size(52.dp))
                }
            }
        }
    }
}

@Composable
private fun ModeLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color(0xFFFFCC00) else Color.White.copy(alpha = 0.75f),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun CamButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White) }
}
