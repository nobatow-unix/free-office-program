package com.icqnew.app.presentation.call

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.data.call.CallManager
import com.icqnew.app.data.call.CallPhase
import com.icqnew.app.presentation.components.Avatar
import com.icqnew.app.presentation.components.IcqIcons
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Full-screen call UI shown on top of everything while a call exists. */
@Composable
fun CallScreen(manager: CallManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    val call = state ?: return
    val context = LocalContext.current
    BackHandler { /* the call stays on screen until it ends */ }

    val acceptPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (ok) manager.accept() else Toast.makeText(context, "Нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
    }
    fun accept() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (call.video && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) manager.accept() else acceptPermissions.launch(needed.toTypedArray())
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallPhase.ACTIVE) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val status = when (call.phase) {
        CallPhase.OUTGOING -> if (call.video) "Видеозвонок…" else "Звоним…"
        CallPhase.INCOMING -> if (call.video) "Входящий видеозвонок" else "Входящий звонок"
        CallPhase.CONNECTING -> "Соединение…"
        CallPhase.ACTIVE -> {
            val sec = ((now - call.startedAt) / 1000).coerceAtLeast(0)
            "%d:%02d".format(sec / 60, sec % 60)
        }
        CallPhase.ENDED -> call.endText ?: "Звонок завершён"
    }
    val showRemoteVideo = call.video && call.remoteVideo && call.phase == CallPhase.ACTIVE

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B2E1E), Color(0xFF050807))))
            .clickable(enabled = false) {},
    ) {
        if (call.video && call.phase != CallPhase.ENDED) {
            if (showRemoteVideo) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(manager.eglBase.eglBaseContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setEnableHardwareScaler(true)
                            manager.attachRemote(this)
                        }
                    },
                    onRelease = {
                        manager.attachRemote(null)
                        it.release()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!call.cameraOff && call.phase != CallPhase.INCOMING) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(manager.eglBase.eglBaseContext, null)
                            setMirror(true)
                            setZOrderMediaOverlay(true)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            manager.attachLocal(this)
                        }
                    },
                    onRelease = {
                        manager.attachLocal(null)
                        it.release()
                    },
                    modifier = if (showRemoteVideo) {
                        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                            .size(110.dp, 160.dp).clip(RoundedCornerShape(16.dp))
                    } else Modifier.fillMaxSize(),
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = if (showRemoteVideo) 24.dp else 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!showRemoteVideo) {
                val pulse = rememberInfiniteTransition(label = "pulse")
                val k by pulse.animateFloat(
                    1f, if (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.ENDED) 1f else 1.08f,
                    infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "k",
                )
                Box(Modifier.scale(k)) { Avatar(call.name, call.peer, call.avatarUrl, false, 128.dp) }
                Spacer(Modifier.height(20.dp))
            }
            Text(call.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(status, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (call.phase) {
                CallPhase.INCOMING -> Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundButton(Icons.Filled.CallEnd, "Отклонить", Color(0xFFE53935), big = true) { manager.decline() }
                    RoundButton(
                        if (call.video) IcqIcons.Video else IcqIcons.Calls, "Ответить", Color(0xFF00C853), big = true,
                    ) { accept() }
                }
                CallPhase.ENDED -> Unit
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(
                            if (call.micOff) Icons.Filled.MicOff else Icons.Filled.Mic,
                            if (call.micOff) "Вкл. микрофон" else "Микрофон",
                            if (call.micOff) Color.White else Color.White.copy(alpha = 0.16f),
                            iconTint = if (call.micOff) Color.Black else Color.White,
                        ) { manager.toggleMic() }
                        RoundButton(
                            Icons.AutoMirrored.Filled.VolumeUp, "Динамик",
                            if (call.speaker) Color.White else Color.White.copy(alpha = 0.16f),
                            iconTint = if (call.speaker) Color.Black else Color.White,
                        ) { manager.toggleSpeaker() }
                        if (call.video) {
                            RoundButton(
                                if (call.cameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam, "Камера",
                                if (call.cameraOff) Color.White else Color.White.copy(alpha = 0.16f),
                                iconTint = if (call.cameraOff) Color.Black else Color.White,
                            ) { manager.toggleCamera() }
                            RoundButton(Icons.Filled.Cameraswitch, "Сменить", Color.White.copy(alpha = 0.16f)) {
                                manager.switchCamera()
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    RoundButton(Icons.Filled.CallEnd, "Завершить", Color(0xFFE53935), big = true) { manager.hangup() }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    color: Color,
    big: Boolean = false,
    iconTint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (big) 72.dp else 58.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = iconTint, modifier = Modifier.size(if (big) 32.dp else 26.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}
