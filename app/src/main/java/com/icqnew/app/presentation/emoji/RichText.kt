package com.icqnew.app.presentation.emoji

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
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
    fun plain(text: String): String = MARK.replace(text) { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "" }

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
                        append(g[7])
                        if (!spoilersOpen) addStyle(SpanStyle(color = Color.Transparent, background = Color(0xFF7A7F87)), start, length)
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
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onLink: (tag: String, value: String) -> Unit = { _, _ -> },
    onLongPress: () -> Unit = {},
) {
    var spoilersOpen by remember(text) { mutableStateOf(false) }
    val styled = remember(text, linkColor, spoilersOpen, codeBackground) { RichFormat.build(text, linkColor, spoilersOpen, codeBackground) }
    val (annotated, keys) = remember(styled) { Emoji.annotateStyled(styled) }
    val inline = remember(keys) {
        keys.associate { key ->
            ("e:$key") to InlineTextContent(Placeholder(1.22f.em, 1.22f.em, PlaceholderVerticalAlign.TextCenter)) {
                AsyncImage(model = Emoji.url(key), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        annotated,
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures(
                onLongPress = { onLongPress() },
                onTap = { pos ->
                    val l = layout ?: return@detectTapGestures
                    val off = l.getOffsetForPosition(pos)
                    val a = annotated.getStringAnnotations(off, off).firstOrNull() ?: return@detectTapGestures
                    if (a.tag == RichFormat.TAG_SPOILER) spoilersOpen = true else onLink(a.tag, a.item)
                },
            )
        },
        color = if (color == Color.Unspecified) LocalContentColor.current else color,
        fontSize = fontSize,
        inlineContent = inline,
        onTextLayout = { layout = it; onTextLayout(it) },
    )
}
