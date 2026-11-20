package com.rajdeep.vesper.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** `*text*forever` (no space) keeps a message permanently. */
private val FOREVER = Regex("""^\*(.+)\*forever$""", RegexOption.DOT_MATCHES_ALL)

object MessageText {
    fun isForever(raw: String) = FOREVER.matches(raw.trim())

    /** Text shown in the bubble: the forever suffix is dropped (a badge shows it instead). */
    fun visible(raw: String): String = FOREVER.matchEntire(raw.trim())?.let { "*${it.groupValues[1]}*" } ?: raw
}

/**
 * WhatsApp-style formatting:
 *   *bold*   _italic_   ~strike~   `inline code`   ```block```   > quote (line start)   - / • list items   ||spoiler||
 * Markers must hug the text (no inner leading/trailing space) and sit at word boundaries, like WhatsApp.
 * URLs become tappable links.
 */
fun formatMessage(raw: String, mono: FontFamily, accent: Color, quoteColor: Color, spoilerBg: Color): AnnotatedString {
    val text = MessageText.visible(raw).trimEnd()
    return buildAnnotatedString {
        val lines = text.split('\n')
        var inBlock = false
        lines.forEachIndexed { li, lineRaw ->
            var line = if (inBlock) lineRaw else lineRaw.trimEnd()
            if (inBlock || line.trimStart().startsWith("```")) {
                withStyle(SpanStyle(fontFamily = mono, background = spoilerBg.copy(alpha = 0.55f))) { append(line.replace("```", "")) }
                if (Regex("```").findAll(line).count() % 2 == 1) inBlock = !inBlock
            } else {
                if (line.startsWith("> ")) {
                    withStyle(SpanStyle(color = quoteColor)) { append("▍ ") }
                    line = line.removePrefix("> ")
                    withStyle(SpanStyle(color = quoteColor, fontStyle = FontStyle.Italic)) { inline(line, mono, accent, spoilerBg) }
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    withStyle(SpanStyle(color = accent)) { append("•  ") }
                    inline(line.drop(2), mono, accent, spoilerBg)
                } else inline(line, mono, accent, spoilerBg)
            }
            if (li < lines.lastIndex) append('\n')
        }
    }
}

private val TOKENS = Regex(
    """(?<![\w*])\*(?=\S)(.+?)(?<=\S)\*(?![\w*])""" + "|" +
        """(?<![\w_\\])_(?=\S)(.+?)(?<=\S)_(?![\w_/])""" + "|" +
        """(?<![\w~])~(?=\S)(.+?)(?<=\S)~(?![\w~])""" + "|" +
        """`([^`\n]+)`""" + "|" +
        """\|\|(.+?)\|\|""" + "|" +
        """(https?://[^\s]+|www\.[^\s]+)""",
)

private fun AnnotatedString.Builder.inline(s: String, mono: FontFamily, accent: Color, spoilerBg: Color) {
    var i = 0
    for (m in TOKENS.findAll(s)) {
        append(s.substring(i, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[1], mono, accent, spoilerBg) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(g[2], mono, accent, spoilerBg) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { inline(g[3], mono, accent, spoilerBg) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = mono, background = spoilerBg.copy(alpha = 0.55f))) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(color = Color.Transparent, background = spoilerBg)) { append(g[5]) }
            g[6].isNotEmpty() -> {
                val url = if (g[6].startsWith("www.")) "https://${g[6]}" else g[6]
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))) { append(g[6]) }
            }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

/** Slash commands understood by the composer. Handled on this phone; nothing odd is sent. */
data class SlashCommand(val name: String, val args: String, val summary: String)

val SLASH_COMMANDS = listOf(
    SlashCommand("/docs", "", "Every command and formatting option"),
    SlashCommand("/about", "", "What Vesper is and how it protects you"),
    SlashCommand("/keep", "message", "Send a message that never disappears"),
    SlashCommand("/me", "action", "Say something in third person, in italics"),
    SlashCommand("/shrug", "[message]", "Append ¯\\_(ツ)_/¯"),
    SlashCommand("/clear", "", "Remove this conversation from this phone"),
)
