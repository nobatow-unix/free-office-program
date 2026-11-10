package com.icqnew.app.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.icqnew.app.data.call.CallManager
import com.icqnew.app.data.call.CallPhase
import com.icqnew.app.data.media.VoicePlayer
import kotlinx.coroutines.delay

/**
 * Bars above the whole app (like Telegram): «Вернуться к звонку» while a folded call goes on,
 * and the mini player of the voice message that keeps playing after leaving its chat.
 * The first visible bar takes the status-bar inset.
 */
@Composable
fun AppTopBars(
    calls: CallManager,
    voice: VoicePlayer,
    /** inline = on the main screen right under «ICQ», as rounded cards (no status-bar inset) */
    inline: Boolean = false,
    onOpenChat: (Long) -> Unit,
) {
    val call by calls.state.collectAsStateWithLifecycle()
    val minimized by calls.minimized.collectAsStateWithLifecycle()
    val v by voice.state.collectAsStateWithLifecycle()
    val c = call
    val showCall = c != null && minimized && c.phase != CallPhase.ENDED && c.phase != CallPhase.INCOMING
    val showVoice = v.playingId != null
    val card = if (inline) Modifier.padding(horizontal = 10.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp)) else Modifier
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(showCall, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
            val text = c?.let { x ->
                if (x.phase == CallPhase.ACTIVE) {
                    val sec = ((now - x.startedAt) / 1000).coerceAtLeast(0)
                    "%d:%02d".format(sec / 60, sec % 60)
                } else "соединение…"
            } ?: ""
            Row(
                Modifier.fillMaxWidth().then(card).background(Color(0xFF22C55E)).clickable { calls.expand() }
                    .then(if (inline) Modifier else Modifier.statusBarsPadding())
                    .padding(horizontal = 14.dp, vertical = if (inline) 9.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Call, null, tint = Color.White, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Вернуться к звонку · ${c?.name ?: ""}", color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(text, color = Color.White, fontSize = 13.sp)
            }
        }
        AnimatedVisibility(showVoice, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val primary = MaterialTheme.colorScheme.primary
            Column(
                Modifier.fillMaxWidth().then(card).background(
                    if (inline) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
                ).then(if (showCall || inline) Modifier else Modifier.statusBarsPadding()),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { if (v.peer != 0L) onOpenChat(v.peer) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).clickable { voice.pauseResume() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (v.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Пауза", tint = primary)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.title.ifBlank { "Голосовое сообщение" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Голосовое · " + fmt(v.positionMs) + " / " + fmt(v.durationMs),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(primary.copy(alpha = 0.14f))
                            .clickable { voice.cycleSpeed() }.padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text((if (v.speed % 1f == 0f) v.speed.toInt().toString() else v.speed.toString()) + "x",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primary)
                    }
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).clickable { voice.close() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, "Закрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                }
                // progress line
                val f = if (v.durationMs > 0) (v.positionMs.toFloat() / v.durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(2.dp).background(primary.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(f).height(2.dp).background(primary))
                }
            }
        }
    }
}

private fun fmt(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
