package com.icqnew.app.presentation.emoji

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * The app's own emoji pictures (Microsoft Fluent Emoji 3D, MIT licence, in assets/emoji).
 * Every emoji in messages, the chat list, reactions and the picker is drawn with these
 * pictures, so it looks the same on every phone.
 */
object Emoji {
    data class Item(val glyph: String, val key: String)
    data class Category(val title: String, val items: List<Item>)

    private val byGlyph = HashMap<String, String>(2048)
    private val bigKeys = HashSet<String>(512)
    @Volatile private var loaded = false
    private var maxChars = 1
    var categories: List<Category> = emptyList()
        private set

    private val titles = mapOf(
        "Smileys & Emotion" to "Смайлы",
        "People & Body" to "Люди",
        "Animals & Nature" to "Природа",
        "Food & Drink" to "Еда",
        "Activities" to "Активности",
        "Travel & Places" to "Путешествия",
        "Objects" to "Объекты",
        "Symbols" to "Символы",
        "Flags" to "Флаги",
    )

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val groups = LinkedHashMap<String, MutableList<Item>>()
            try {
                context.assets.open("emoji/index.tsv").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val p = line.split('\t')
                        if (p.size < 3) continue
                        val glyph = p[1]
                        val key = p[2]
                        val n = norm(glyph)
                        byGlyph[n] = key
                        if (n.length > maxChars) maxChars = n.length
                        groups.getOrPut(p[0]) { mutableListOf() }.add(Item(glyph, key))
                    }
                }
            } catch (e: Exception) {
                // no emoji pictures: system emoji are used
            }
            categories = groups.filter { it.key != "Flags" }.map { (g, items) -> Category(titles[g] ?: g, items) }
            try {
                context.assets.open("stickers/list.txt").bufferedReader().useLines { l -> l.forEach { bigKeys.add(it.trim()) } }
            } catch (e: Exception) {
                // no high-resolution set
            }
            loaded = true
        }
    }

    private fun norm(s: String): String = s.replace("️", "")

    fun keyOf(glyph: String): String? = byGlyph[norm(glyph)]

    fun url(key: String): String = "file:///android_asset/emoji/$key.webp"

    /** High-resolution picture (stickers, big emoji) when available. */
    fun bigUrl(key: String): String =
        if (key in bigKeys) "file:///android_asset/stickers/$key.webp" else url(key)

    private fun mayStart(cp: Int): Boolean = cp >= 0x2000 || cp == 0xA9 || cp == 0xAE

    /** One emoji (or a plain text piece) of a string. */
    private data class Part(val text: String, val key: String?)

    private fun split(text: String): List<Part> {
        val out = ArrayList<Part>()
        val plain = StringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            if (mayStart(cp) && byGlyph.isNotEmpty()) {
                // longest match: collect up to maxChars meaningful chars (FE0F is skipped)
                var bestEnd = -1
                var bestKey: String? = null
                val sb = StringBuilder()
                var j = i
                while (j < n && sb.length <= maxChars) {
                    val c = text[j]
                    j++
                    if (c == '️') {
                        if (bestEnd == j - 1) bestEnd = j // absorb a trailing FE0F
                        continue
                    }
                    sb.append(c)
                    if (Character.isHighSurrogate(c)) continue
                    val k = byGlyph[sb.toString()]
                    if (k != null) {
                        bestEnd = j
                        bestKey = k
                    }
                }
                if (bestKey != null && bestEnd > i) {
                    if (plain.isNotEmpty()) {
                        out.add(Part(plain.toString(), null))
                        plain.setLength(0)
                    }
                    out.add(Part(text.substring(i, bestEnd), bestKey))
                    i = bestEnd
                    continue
                }
            }
            plain.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        if (plain.isNotEmpty()) out.add(Part(plain.toString(), null))
        return out
    }

    /** Text with emoji replaced by inline pictures. */
    fun annotate(text: String): Pair<AnnotatedString, Set<String>> {
        val parts = split(text)
        val keys = HashSet<String>()
        val s = buildAnnotatedString {
            for (p in parts) {
                if (p.key == null) append(p.text)
                else {
                    keys.add(p.key)
                    appendInlineContent("e:" + p.key, p.text)
                }
            }
        }
        return s to keys
    }

    /** Same as [annotate] but keeps the styles of an already formatted text (bold, links…). */
    fun annotateStyled(styled: AnnotatedString): Pair<AnnotatedString, Set<String>> {
        val parts = split(styled.text)
        val keys = HashSet<String>()
        var pos = 0
        val s = buildAnnotatedString {
            for (p in parts) {
                val end = pos + p.text.length
                if (p.key == null) append(styled.subSequence(pos, end))
                else {
                    keys.add(p.key)
                    appendInlineContent("e:" + p.key, p.text)
                }
                pos = end
            }
        }
        return s to keys
    }

    /** Number of emoji if the text consists ONLY of 1..3 emoji (shown big, without a bubble), else 0. */
    fun onlyEmojiCount(text: String): Int {
        val t = text.trim()
        if (t.isEmpty() || t.length > 40) return 0
        val parts = split(t).filter { it.key != null || it.text.isNotBlank() }
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.key == null }) return 0
        return parts.size
    }
}

/** Picture of one emoji. */
@Composable
fun EmojiImage(glyph: String, size: Dp, modifier: Modifier = Modifier) {
    val key = remember(glyph) { Emoji.keyOf(glyph) }
    if (key != null) {
        AsyncImage(
            model = if (size > 48.dp) Emoji.bigUrl(key) else Emoji.url(key),
            contentDescription = glyph, modifier = modifier.size(size),
        )
    } else {
        Box(modifier.size(size)) { Text(glyph, fontSize = (size.value * 0.8f).sp) }
    }
}

/** Text where every emoji is drawn with the app's emoji pictures. */
@Composable
fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    style: TextStyle = LocalTextStyle.current,
    emojiScale: Float = 1.22f,
    big: Boolean = false,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {},
) {
    val (annotated, keys) = remember(text) { Emoji.annotate(text) }
    val inline = remember(keys, emojiScale, big) {
        keys.associate { key ->
            ("e:$key") to InlineTextContent(
                Placeholder(emojiScale.em, emojiScale.em, PlaceholderVerticalAlign.TextCenter)
            ) {
                AsyncImage(
                    model = if (big) Emoji.bigUrl(key) else Emoji.url(key),
                    contentDescription = null, modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    Text(
        annotated,
        modifier = modifier,
        color = if (color == Color.Unspecified) LocalContentColor.current else color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        inlineContent = inline,
        style = style,
        onTextLayout = onTextLayout,
    )
}
