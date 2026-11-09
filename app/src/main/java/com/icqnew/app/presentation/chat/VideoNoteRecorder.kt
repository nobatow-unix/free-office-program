package com.icqnew.app.presentation.chat

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val MAX_MS = 60_000L

/**
 * Round video message recorded right here (no external camera app), like Telegram:
 * round preview, progress ring (up to 60 s), front / back camera switch and flash
 * (the back camera turns on its torch, the front one lights the screen up).
 * Tap the red button to start, tap again to send; ✕ throws the recording away.
 */
@SuppressLint("MissingPermission") // CAMERA + RECORD_AUDIO are checked before this dialog is shown
@Composable
fun VideoNoteRecorder(onDismiss: () -> Unit, onDone: (File, Long) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView: can be clipped round
        }
    }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var front by remember { mutableStateOf(true) }
    var flash by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var cancelled by remember { mutableStateOf(false) }

    // (re)bind the camera when front / back changes
    LaunchedEffect(front) {
        val p = provider ?: suspendCancellableCoroutine { cont ->
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(ctx))
        }.also { provider = it }
        try {
            p.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            camera = p.bindToLifecycle(lifecycle, selector, preview, videoCapture)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Камера недоступна", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    // flash: torch on the back camera
    LaunchedEffect(flash, front, camera) {
        runCatching { camera?.cameraControl?.enableTorch(flash && !front) }
    }
    // flash on the front camera: full screen brightness + white light around the circle
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(flash, front, window) {
        window?.let { w ->
            val a = w.attributes
            a.screenBrightness = if (flash && front) 1f else -1f
            w.attributes = a
        }
    }
    // timer + auto stop at 60 s
    LaunchedEffect(recording) {
        while (recording != null) {
            elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= MAX_MS) recording?.stop()
            delay(50)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (recording != null) cancelled = true // closed while recording: throw it away
            recording?.close()
            provider?.unbindAll()
        }
    }

    fun start() {
        val dir = File(ctx.filesDir, "outbox").apply { mkdirs() }
        val file = File(dir, "videonote_" + System.currentTimeMillis() + ".mp4")
        val opts = FileOutputOptions.Builder(file).setDurationLimitMillis(MAX_MS).build()
        cancelled = false
        startedAt = System.currentTimeMillis()
        elapsed = 0
        recording = videoCapture.output.prepareRecording(ctx, opts).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    val dur = ev.recordingStats.recordedDurationNanos / 1_000_000
                    val ok = !ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    recording = null
                    when {
                        cancelled -> file.delete()
                        !ok || dur < 700 || file.length() == 0L -> {
                            file.delete()
                            Toast.makeText(ctx, "Слишком коротко — нажмите и запишите ещё раз", Toast.LENGTH_SHORT).show()
                        }
                        else -> onDone(file, dur)
                    }
                }
            }
    }

    Dialog(onDismissRequest = { cancelled = true; recording?.stop(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val frontLight = flash && front
        Box(
            Modifier.fillMaxSize().background(if (frontLight) Color(0xFFFFF6E8) else Color(0xEE0B0E12)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val recordingNow = recording != null
                val scale by animateFloatAsState(if (recordingNow) 1.04f else 1f, spring(dampingRatio = 0.6f), label = "note")
                Box(Modifier.size(300.dp).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
                    AndroidView({ previewView }, Modifier.size(280.dp).clip(CircleShape))
                    // progress ring
                    val progress = (elapsed.toFloat() / MAX_MS).coerceIn(0f, 1f)
                    Canvas(Modifier.size(296.dp)) {
                        val stroke = 5.dp.toPx()
                        drawArc(
                            Color.White.copy(alpha = 0.18f), 0f, 360f, false,
                            topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(stroke),
                        )
                        if (recordingNow) drawArc(
                            Color(0xFFFF3B30), -90f, 360f * progress, false,
                            topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                val sec = elapsed / 1000
                Text(
                    if (recordingNow) "● %d:%02d".format(sec / 60, sec % 60) else "Нажмите, чтобы начать запись",
                    color = if (frontLight) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
            }
            // controls
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 30.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Filled.Close, "Отмена", frontLight) {
                    cancelled = true
                    recording?.stop()
                    onDismiss()
                }
                RoundButton(if (flash) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Вспышка", frontLight) { flash = !flash }
                // record / send
                val recordingNow = recording != null
                Box(
                    Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp)
                        .clip(if (recordingNow) RoundedCornerShape(12.dp) else CircleShape)
                        .background(Color(0xFFFF3B30))
                        .clickable { if (recordingNow) recording?.stop() else start() },
                    contentAlignment = Alignment.Center,
                ) {}
                AnimatedVisibility(!recordingNow, enter = fadeIn(), exit = fadeOut()) {
                    RoundButton(Icons.Filled.Cameraswitch, "Сменить камеру", frontLight) { front = !front }
                }
                if (recordingNow) Spacer(Modifier.size(52.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, light: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(if (light) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (light) Color.Black else Color.White) }
}
