package com.icqnew.app.presentation.emoji

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import coil.compose.AsyncImage

/**
 * Telegram-like formatting in messages:
 *   **жирный**  __курсив__  ~~зачёркнутый~~  --подчёркнутый--  `моно`  ```код```  ||скрытый||
 * plus clickable links, phone numbers and @mentions.
 */
object RichFormat {
    const val TAG_URL = "url"
    const val TAG_PHONE = "phone"
    const val TAG_MENTION = "mention"
    const val TAG_SPOILER = "spoiler"

    private val MARK = Regex(
        "```([\\s\\S]+?)```|`([^`\\n]+)`|\\*\\*([\\s\\S]+?)\\*\\*|__([\\s\\S]+?)__|~~([\\s\\S]+?)~~|--([^\\n]+?)--|\\|\\|([\\s\\S]+?)\\|\\|"
    )
    val URL = Regex("(?i)\\b((?:https?://|www\\.)[^\\s<>\"]+[^\\s<>\".,;:!?)\\]'])")
    val PHONE = Regex("(?<![\\w+])(\\+?\\d[\\d \\-()]{6,16}\\d)(?!\\w)")
    val MENTION = Regex("(?<![\\w@])@([A-Za-z0-9_]{3,32})")

    /** Text without the formatting marks (for previews / notifications). */
    fun plain(text: String): String = MARK.replace(text) { m ->
        if (m.groupValues[7].isNotEmpty()) "▒".repeat(m.groupValues[7].length.coerceIn(3, 12)) // spoiler stays hidden
        else m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
    }

    fun build(text: String, linkColor: Color, spoilersOpen: Boolean, codeBg: Color): AnnotatedString {
        // 1) formatting marks
        val styled = buildAnnotatedString {
            var last = 0
            for (m in MARK.findAll(text)) {
                append(text.substring(last, m.range.first))
                val g = m.groupValues
                val start = length
                when {
                    g[1].isNotEmpty() -> { append(g[1].trim('\n')); addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg), start, length) }
                    g[2].isNotEmpty() -> { append(g[2]); addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg), start, length) }
                    g[3].isNotEmpty() -> { append(g[3]); addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length) }
                    g[4].isNotEmpty() -> { append(g[4]); addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length) }
                    g[5].isNotEmpty() -> { append(g[5]); addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length) }
                    g[6].isNotEmpty() -> { append(g[6]); addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, length) }
                    g[7].isNotEmpty() -> {
                        append(g[7]) // covered by animated particles in RichText until tapped
                        addStringAnnotation(TAG_SPOILER, "1", start, length)
                    }
                }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
        // 2) links, phones, mentions on top of the clean text
        val plainText = styled.text
        return buildAnnotatedString {
            append(styled)
            fun mark(tag: String, r: IntRange, value: String) {
                addStyle(SpanStyle(color = linkColor, textDecoration = if (tag == TAG_URL) TextDecoration.Underline else null), r.first, r.last + 1)
                addStringAnnotation(tag, value, r.first, r.last + 1)
            }
            val taken = ArrayList<IntRange>()
            for (m in URL.findAll(plainText)) { taken.add(m.range); mark(TAG_URL, m.range, m.value) }
            for (m in MENTION.findAll(plainText)) if (taken.none { it.first <= m.range.first && m.range.first <= it.last }) {
                taken.add(m.range); mark(TAG_MENTION, m.range, m.groupValues[1])
            }
            for (m in PHONE.findAll(plainText)) {
                if (taken.any { it.first <= m.range.first && m.range.first <= it.last }) continue
                if (m.value.count { it.isDigit() } < 8) continue
                mark(TAG_PHONE, m.range, m.value)
            }
        }
    }

    fun firstUrl(text: String): String? = URL.find(plain(text))?.value?.let { if (it.startsWith("www.", true)) "https://$it" else it }
}

/** A message text with formatting, emoji pictures and clickable links / phones / @mentions / spoilers. */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    linkColor: Color = Color(0xFF4FA3FF),
    fontSize: TextUnit = TextUnit.Unspecified,
    codeBackground: Color = Color(0x33000000),
    spoilerColor: Color = Color.Unspecified,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onLink: (tag: String, value: String) -> Unit = { _, _ -> },
    onLongPress: () -> Unit = {},
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    /** false = only shows the formatting (chat list previews): taps go to the parent row */
    interactive: Boolean = true,
) {
    val styled = remember(text, linkColor, codeBackground) { RichFormat.build(text, linkColor, false, codeBackground) }
    val (annotated, keys) = remember(styled) { Emoji.annotateStyled(styled) }
    val inline = remember(keys) {
        keys.associate { key ->
            ("e:$key") to InlineTextContent(Placeholder(1.22f.em, 1.22f.em, PlaceholderVerticalAlign.TextCenter)) {
                AsyncImage(model = Emoji.url(key), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
    }
    val textColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val coverColor = if (spoilerColor == Color.Unspecified) MaterialTheme.colorScheme.surface else spoilerColor
    // ---- spoiler: animated "dust" over the hidden text, revealed with a growing circle from the tap ----
    // offsets are the same before / after emoji pictures are inserted; `styled` keeps whole ranges
    val spoilers = remember(styled) { styled.getStringAnnotations(RichFormat.TAG_SPOILER, 0, styled.length) }
    val reveal = remember(text) { Animatable(0f) }
    var tapAt by remember(text) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val hidden = spoilers.isNotEmpty() && reveal.value < 1f
    val time = if (hidden) {
        rememberInfiniteTransition(label = "spoiler").animateFloat(
            0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "dust",
        )
    } else null
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        annotated,
        modifier = modifier
            .then(if (!interactive) Modifier else Modifier.pointerInput(annotated) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { pos ->
                        val l = layout ?: return@detectTapGestures
                        val off = l.getOffsetForPosition(pos)
                        val inSpoiler = reveal.value < 1f && spoilers.any { off in it.start until it.end }
                        val a = if (inSpoiler) null else annotated.getStringAnnotations(off, off).firstOrNull()
                        if (a == null && !inSpoiler) return@detectTapGestures
                        if (a == null) {
                            if (reveal.value == 0f) {
                                tapAt = pos
                                scope.launch { reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
                            }
                        } else onLink(a.tag, a.item)
                    },
                )
            })
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                if (spoilers.isEmpty() || reveal.value >= 1f) return@drawWithContent
                val t = time?.value ?: 0f
                val radius = reveal.value * (size.width + size.height)
                val dot = 1.dp.toPx()
                for (a in spoilers) {
                    val path = l.getPathForRange(a.start, a.end)
                    val b = path.getBounds()
                    clipPath(path) {
                        val cut = Path().apply { addOval(Rect(tapAt, radius)) }
                        clipPath(cut, ClipOp.Difference) {
                            drawRect(coverColor, b.topLeft, b.size)
                            val rnd = java.util.Random(a.start * 31L + a.end)
                            val n = (b.width * b.height / (dot * dot * 22f)).toInt().coerceIn(10, 600)
                            repeat(n) {
                                val sx = rnd.nextFloat(); val sy = rnd.nextFloat()
                                val sp = 0.4f + rnd.nextFloat(); val ph = rnd.nextFloat()
                                val dir = if (rnd.nextBoolean()) 1f else -1f
                                val r = dot * (0.6f + rnd.nextFloat() * 0.6f)
                                val x = b.left + (((sx + dir * t * sp) % 1f) + 1f) % 1f * b.width
                                val y = b.top + (((sy + t * sp * 0.6f * -dir) % 1f) + 1f) % 1f * b.height
                                val alpha = 0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin((t * 6f + ph) * 6.2832f))
                                drawCircle(textColor.copy(alpha = alpha * textColor.alpha), r, Offset(x, y))
                            }
                        }
                    }
                }
            },
        color = textColor,
        fontSize = fontSize,
        inlineContent = inline,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout = it; onTextLayout(it) },
    )
}
