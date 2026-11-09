package com.icqnew.app.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.icqnew.app.presentation.theme.IcqGreen
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Our own QR scanner: full screen, upright (no need to turn the phone), green frame with a moving line.
 * Used to log in to the web version.
 */
@Composable
fun QrScannerDialog(title: String, hint: String, onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) onDismiss()
    }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                var done by remember { mutableStateOf(false) }
                AndroidView(
                    factory = { ctx ->
                        DecoratedBarcodeView(ctx).apply {
                            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                            setStatusText("")
                            viewFinder.visibility = android.view.View.GONE // we draw our own frame
                            decodeContinuous(BarcodeCallback { r ->
                                val text = r?.text ?: return@BarcodeCallback
                                if (!done) {
                                    done = true
                                    pause()
                                    onResult(text)
                                }
                            })
                            resume()
                        }
                    },
                    onRelease = { it.pause() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ScannerOverlay()
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                ) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
                Spacer(Modifier.height(24.dp))
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Text(
                hint, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp),
            )
        }
    }
}

@Composable
private fun ScannerOverlay() {
    val t = rememberInfiniteTransition(label = "scan")
    val line by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "line")
    val green = IcqGreen
    Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val side = size.minDimension * 0.68f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, Offset(left, top), Size(side, side), CornerRadius(32f), blendMode = BlendMode.Clear)
        drawRoundRect(green, Offset(left, top), Size(side, side), CornerRadius(32f), style = Stroke(width = 6f))
        val y = top + 16f + (side - 32f) * line
        drawLine(green.copy(alpha = 0.85f), Offset(left + 24f, y), Offset(left + side - 24f, y), strokeWidth = 4f)
    }
}
