package com.icqnew.app.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.foundation.clickable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Verified
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.icqnew.app.presentation.emoji.EmojiText
import androidx.compose.material3.Icon
import com.icqnew.app.R
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.icqnew.app.presentation.theme.IcqGreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Base URL of the server, used to resolve relative avatar links. */
val LocalServerUrl = staticCompositionLocalOf { "" }

/** uin -> 1 (has an unseen story, green ring) / 2 (all stories seen, grey ring). */
val LocalStoryRings = androidx.compose.runtime.compositionLocalOf<Map<Long, Int>> { emptyMap() }

/** The ICQ logo: black circle with the green five-petal flower (res/drawable/ic_logo.xml). */
@Composable
fun IcqLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_logo),
        contentDescription = "ICQ",
        modifier = modifier.size(size),
    )
}

private val avatarColors = listOf(
    Color(0xFF26A69A), Color(0xFF5C6BC0), Color(0xFFEF6C00), Color(0xFF8E24AA),
    Color(0xFF3949AB), Color(0xFFD81B60), Color(0xFF00897B), Color(0xFF6D4C41),
)

/** Round avatar (image if the user has one, otherwise coloured initials) + green online dot. */
@Composable
fun Avatar(
    name: String,
    uin: Long,
    avatarUrl: String?,
    online: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
    isChannel: Boolean = false,
    isSelf: Boolean = false,
    isBot: Boolean = false,
    storyRing: Boolean = true,
) {
    val base = LocalServerUrl.current
    val url = avatarUrl?.let { if (it.startsWith("/")) base + it else it }
    val ring = if (storyRing && !isGroup && !isChannel && !isSelf && uin > 0) LocalStoryRings.current[uin] ?: 0 else 0
    val outer = size
    Box(modifier.size(outer)) {
      if (ring > 0) {
        Box(
            Modifier.matchParentSize().border(
                if (ring == 1) (outer * 0.045f).coerceAtLeast(2.dp) else 1.5.dp,
                if (ring == 1) IcqGreen else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
        )
      }
      val size = if (ring > 0) outer * 0.84f else outer
      Box(Modifier.align(Alignment.Center).size(size)) {
        if (isSelf) {
            Box(
                Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Bookmark, null, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
        } else if ((isBot || uin == com.icqnew.app.domain.BOT_UIN) && url.isNullOrBlank()) {
            BotLogo(size)
        } else if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(avatarColors[(abs(uin) % avatarColors.size).toInt()]),
                contentAlignment = Alignment.Center,
            ) {
                if (isChannel) {
                    Icon(IcqIcons.Channels, null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
                } else if (isGroup) {
                    Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(size * 0.58f))
                } else {
                    Text(
                        text = name.trim().take(1).uppercase().ifEmpty { "?" },
                        color = Color.White,
                        fontSize = (size.value * 0.42f).sp,
                    )
                }
            }
        }
      }
        if (online && !isGroup && uin != com.icqnew.app.domain.BOT_UIN) {
            val dot = outer * 0.28f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dot)
                    .clip(CircleShape)
                    .background(IcqGreen)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
            )
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("dd.MM")
private val fullFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val ru = Locale("ru")
private val dayFmt = DateTimeFormatter.ofPattern("d MMMM", ru)
private val dayYearFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)

/** Time for today, "Вчера", dd.MM for other days. */
fun formatListTime(ts: Long): String {
    val dt = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (dt.toLocalDate()) {
        today -> dt.format(timeFmt)
        today.minusDays(1) -> "Вчера"
        else -> if (dt.year == today.year) dt.format(dateFmt) else dt.format(fullFmt)
    }
}

fun formatClock(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(timeFmt)

/** Header between messages of different days: "Сегодня", "Вчера", "5 ноября". */
fun formatDay(ts: Long): String {
    val d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> if (d.year == today.year) d.format(dayFmt) else d.format(dayYearFmt)
    }
}

fun sameDay(a: Long, b: Long): Boolean {
    val z = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(z).toLocalDate() == Instant.ofEpochMilli(b).atZone(z).toLocalDate()
}

private fun minutesWord(n: Long): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1L && m100 != 11L -> "минуту"
        m10 in 2L..4L && m100 !in 12L..14L -> "минуты"
        else -> "минут"
    }
}

private fun hoursWord(n: Long): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1L && m100 != 11L -> "час"
        m10 in 2L..4L && m100 !in 12L..14L -> "часа"
        else -> "часов"
    }
}

/**
 * "в сети" / "был(а) только что" / "был(а) 5 минут назад" / "был(а) сегодня в 12:30" /
 * "был(а) вчера в 21:10" / "был(а) 5 ноября в 10:00" / "был(а) недавно" (when the time is hidden).
 */
fun formatPresence(online: Boolean, lastSeen: Long, uin: Long = 0): String {
    if (uin == com.icqnew.app.domain.BOT_UIN) return "бот"
    if (online) return "в сети"
    if (lastSeen <= 0) return "был(а) недавно"
    val diff = System.currentTimeMillis() - lastSeen
    val min = diff / 60_000
    val dt = Instant.ofEpochMilli(lastSeen).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when {
        diff < 60_000 -> "был(а) только что"
        min < 60 -> "был(а) $min ${minutesWord(min)} назад"
        min < 6 * 60 && dt.toLocalDate() == today -> (min / 60).let { h -> "был(а) $h ${hoursWord(h)} назад" }
        dt.toLocalDate() == today -> "был(а) сегодня в ${dt.format(timeFmt)}"
        dt.toLocalDate() == today.minusDays(1) -> "был(а) вчера в ${dt.format(timeFmt)}"
        dt.year == today.year -> "был(а) ${dt.format(dayFmt)} в ${dt.format(timeFmt)}"
        else -> "был(а) ${dt.format(dayYearFmt)}"
    }
}

/** Russian plural for "участник". */
fun membersText(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> "участник"
        m10 in 2..4 && m100 !in 12..14 -> "участника"
        else -> "участников"
    }
    return "$n $w"
}


/** Green "verified" tick (set by the administrator; chat bots always have it). */
val VerifiedGreen = Color(0xFF00C853)

@Composable
fun VerifiedBadge(size: Dp = 16.dp, modifier: Modifier = Modifier, bot: Boolean = false) {
    // bots get a blue tick, people / channels a green one
    Icon(Icons.Filled.Verified, if (bot) "Бот" else "Подтверждено", tint = if (bot) BotBlue else VerifiedGreen, modifier = modifier.size(size))
}

val BotBlue = Color(0xFF2AABEE)

/** Name (with emoji pictures) followed by the green tick when verified. */
@Composable
fun NameLine(
    name: String,
    verified: Boolean,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = FontWeight.Bold,
    color: Color = Color.Unspecified,
    bot: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        EmojiText(
            name, fontSize = fontSize, fontWeight = fontWeight, color = color, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (verified || bot) {
            Spacer(Modifier.width(4.dp))
            VerifiedBadge(if (fontSize.isSp && fontSize.value > 20) 20.dp else 16.dp, bot = bot)
        }
    }
}

/** Three bouncing dots — the animated "печатает…" indicator. */
@Composable
fun TypingDots(color: Color, modifier: Modifier = Modifier, dot: Dp = 5.dp) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val y by t.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.keyframes {
                        durationMillis = 1200
                        0f at 0 + i * 160
                        1f at 200 + i * 160
                        0f at 400 + i * 160
                    },
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .graphicsLayer(translationY = -y * dot.value * 1.6f, alpha = 0.45f + 0.55f * y)
                    .size(dot)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** "Имя печатает" text + bouncing dots. */
@Composable
fun TypingLabel(text: String, color: Color, fontSize: TextUnit = 12.sp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, fontSize = fontSize, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        TypingDots(color, Modifier.padding(top = 3.dp), dot = 4.dp)
    }
}

/** Click without the ripple / clipping (keeps the avatar's online dot whole). */
fun Modifier.plainClick(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}


/** Picture of the ICQ bot: a friendly white robot on a green gradient, with the ICQ flower on its antenna. */
@Composable
fun BotLogo(size: Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size).clip(CircleShape)) {
        val w = this.size.width
        drawRect(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFF2EE57A), Color(0xFF0A8F55), Color(0xFF066B44)),
                start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(w, w),
            )
        )
        // soft glow
        drawCircle(Color.White.copy(alpha = 0.12f), radius = w * 0.42f, center = androidx.compose.ui.geometry.Offset(w * 0.3f, w * 0.25f))
        val headW = w * 0.56f
        val headH = w * 0.42f
        val headL = (w - headW) / 2
        val headT = w * 0.38f
        // antenna + flower dot
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(w / 2, headT), androidx.compose.ui.geometry.Offset(w / 2, w * 0.24f), strokeWidth = w * 0.035f)
        for (k in 0 until 5) {
            val a = Math.toRadians(k * 72.0 - 90)
            drawCircle(
                Color(0xFF22FF01), radius = w * 0.035f,
                center = androidx.compose.ui.geometry.Offset(w / 2 + (w * 0.045f * kotlin.math.cos(a)).toFloat(), w * 0.2f + (w * 0.045f * kotlin.math.sin(a)).toFloat()),
            )
        }
        drawCircle(Color(0xFF0A8F55), radius = w * 0.02f, center = androidx.compose.ui.geometry.Offset(w / 2, w * 0.2f))
        // ears
        drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(headL - w * 0.05f, headT + headH * 0.3f),
            androidx.compose.ui.geometry.Size(w * 0.07f, headH * 0.4f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f))
        drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(headL + headW - w * 0.02f, headT + headH * 0.3f),
            androidx.compose.ui.geometry.Size(w * 0.07f, headH * 0.4f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f))
        // head
        drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(headL, headT), androidx.compose.ui.geometry.Size(headW, headH),
            androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
        // face screen
        val fL = headL + headW * 0.12f
        val fT = headT + headH * 0.16f
        drawRoundRect(Color(0xFF0B3D2A), androidx.compose.ui.geometry.Offset(fL, fT),
            androidx.compose.ui.geometry.Size(headW * 0.76f, headH * 0.66f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
        // eyes
        drawCircle(Color(0xFF22FF01), radius = w * 0.045f, center = androidx.compose.ui.geometry.Offset(w / 2 - headW * 0.17f, fT + headH * 0.27f))
        drawCircle(Color(0xFF22FF01), radius = w * 0.045f, center = androidx.compose.ui.geometry.Offset(w / 2 + headW * 0.17f, fT + headH * 0.27f))
        // smile
        drawArc(
            Color(0xFF22FF01), startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w / 2 - headW * 0.16f, fT + headH * 0.22f),
            size = androidx.compose.ui.geometry.Size(headW * 0.32f, headH * 0.3f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.025f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
