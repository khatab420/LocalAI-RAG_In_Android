package com.example.localai.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * A deliberately small Markdown reader: enough for what chat models actually emit
 * (headings, emphasis, lists, quotes, fenced code, simple tables) and safe on
 * half-streamed input, where the closing fence / bracket may not exist yet.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val items: List<MdItem>) : MdBlock
    data class Numbered(val items: List<MdItem>, val start: Int) : MdBlock
    data class Code(val code: String, val language: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

data class MdItem(val text: String, val level: Int = 0)

object Markdown {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val RULE = Regex("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})\\s*(\\S*)")
    private val TABLE_SEP = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")

    // ---------------------------------------------------------------- blocks

    fun parse(source: String): List<MdBlock> =
        parseBlocks(source.replace("\r\n", "\n").replace('\r', '\n').split('\n'))

    private fun parseBlocks(lines: List<String>): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // fenced code
            val fence = FENCE.find(raw)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val language = fence.groupValues[2].trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !isClosingFence(lines[i], marker)) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++
                out += MdBlock.Code(body.toString().trimEnd('\n'), language)
                continue
            }

            val heading = HEADING.find(trimmed)
            if (heading != null) {
                out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                i++
                continue
            }

            // setext heading: "Title" followed by an ==== underline
            val underline = if (i + 1 < lines.size) lines[i + 1].trim() else ""
            if (underline.length >= 1 && underline.all { it == '=' }) {
                out += MdBlock.Heading(1, trimmed)
                i += 2
                continue
            }

            if (RULE.matches(trimmed)) {
                out += MdBlock.Rule
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                val quoted = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    var line = lines[i].trimStart().removePrefix(">")
                    if (line.startsWith(" ")) line = line.substring(1)
                    quoted += line
                    i++
                }
                out += MdBlock.Quote(parseBlocks(quoted))
                continue
            }

            if (isTableStart(lines, i)) {
                val header = splitRow(raw)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i])
                    i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }

            if (isListItem(raw)) {
                val numberedFrom = NUMBERED.find(raw)?.groupValues?.get(2)?.toIntOrNull()
                val items = ArrayList<MdItem>()
                while (i < lines.size) {
                    val line = lines[i]
                    if (line.isBlank()) {
                        // a single blank line is only "inside" the list when an item follows
                        if (i + 1 < lines.size && isListItem(lines[i + 1])) {
                            i++
                            continue
                        }
                        break
                    }
                    val bullet = BULLET.find(line)
                    val numbered = NUMBERED.find(line)
                    when {
                        bullet != null -> items += MdItem(
                            text = bullet.groupValues[2].trim(),
                            level = indentLevel(bullet.groupValues[1])
                        )
                        numbered != null -> items += MdItem(
                            text = numbered.groupValues[3].trim(),
                            level = indentLevel(numbered.groupValues[1])
                        )
                        line.startsWith("  ") || line.startsWith("\t") -> {
                            if (items.isEmpty()) break
                            items[items.lastIndex] = items.last().copy(text = items.last().text + " " + line.trim())
                        }
                        else -> break
                    }
                    i++
                }
                out += if (numberedFrom != null) MdBlock.Numbered(items, numberedFrom) else MdBlock.Bullet(items)
                continue
            }

            // paragraph: keep single line breaks, stop at anything that starts a new block
            val paragraph = StringBuilder()
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) break
                if (paragraph.isNotEmpty() && startsBlock(lines, i)) break
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trim())
                i++
            }
            if (paragraph.isNotEmpty()) out += MdBlock.Paragraph(paragraph.toString())
        }
        return out
    }

    private fun isClosingFence(line: String, marker: String): Boolean {
        val t = line.trim()
        return t.length >= marker.length && t.startsWith(marker) && t.all { it == marker[0] }
    }

    private fun isListItem(line: String): Boolean {
        if (line.isBlank()) return false
        if (BULLET.containsMatchIn(line)) return true
        return NUMBERED.containsMatchIn(line)
    }

    private fun startsBlock(lines: List<String>, index: Int): Boolean {
        val raw = lines[index]
        val trimmed = raw.trim()
        return trimmed.isEmpty() ||
            FENCE.containsMatchIn(raw) ||
            HEADING.matches(trimmed) ||
            RULE.matches(trimmed) ||
            trimmed.startsWith(">") ||
            isListItem(raw) ||
            isTableStart(lines, index)
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val head = lines[index]
        val sep = lines[index + 1].trim()
        if (!head.contains('|')) return false
        if (!sep.contains('-')) return false
        return TABLE_SEP.matches(sep)
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        if (s.isBlank()) return emptyList()
        return s.split('|').map { it.trim() }
    }

    private fun indentLevel(spaces: String): Int =
        (spaces.replace("\t", "  ").length / 2).coerceIn(0, 3)

    // ---------------------------------------------------------------- inline

    private val DEFAULT_CODE = SpanStyle(fontFamily = FontFamily.Monospace)

    fun inline(
        text: String,
        codeStyle: SpanStyle = DEFAULT_CODE,
        linkColor: Color = Color.Unspecified
    ): AnnotatedString = buildAnnotatedString {
        appendInline(text, this, SpanStyle(), codeStyle, linkColor)
    }

    /** Tags models like to sprinkle into answers; they must not show up as text. */
    private val HTML_TAGS = setOf(
        "br", "b", "i", "em", "strong", "code", "p", "span", "ul", "ol", "li",
        "div", "small", "sup", "sub", "u", "s", "font", "hr", "tbody", "tr", "td", "th"
    )

    private fun appendInline(
        src: String,
        out: AnnotatedString.Builder,
        base: SpanStyle,
        codeStyle: SpanStyle,
        linkColor: Color
    ) {
        val literal = StringBuilder()

        // "a < b > c" must stay intact: a real tag never has a space next to its name
        fun tagName(from: Int, end: Int): String {
            val raw = src.substring(from + 1, end)
            if (raw.isEmpty() || raw.first() == ' ' || raw.last() == ' ') return ""
            return raw.removePrefix("/").removeSuffix("/").substringBefore(' ').lowercase()
        }

        fun tagEnd(from: Int): Int {
            val end = src.indexOf('>', from)
            if (end < 0 || end - from > 24) return -1
            return if (tagName(from, end) in HTML_TAGS) end else -1
        }

        fun flush() {
            if (literal.isEmpty()) return
            out.pushStyle(base)
            out.append(literal.toString())
            out.pop()
            literal.setLength(0)
        }

        var i = 0
        while (i < src.length) {
            val c = src[i]

            // escaped punctuation
            if (c == '\\' && i + 1 < src.length && src[i + 1] in "\\`*_{}[]()#+-.!~|>") {
                literal.append(src[i + 1])
                i += 2
                continue
            }

            // inline code
            if (c == '`') {
                val run = runLength(src, i, '`')
                val close = findRun(src, i + run, '`', run)
                if (close < 0) {
                    literal.append(src, i, i + run)
                    i += run
                } else {
                    flush()
                    out.pushStyle(base.merge(codeStyle))
                    out.append(src.substring(i + run, close))
                    out.pop()
                    i = close + run
                }
                continue
            }

            // html the model typed, e.g. <br>, <b>bold</b>: keep the text, drop the tags
            if (c == '<') {
                val end = tagEnd(i)
                if (end > 0) {
                    val name = tagName(i, end)
                    val closing = src[i + 1] == '/'
                    val pairStyle = when (name) {
                        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                        "code" -> codeStyle
                        else -> null
                    }
                    if (pairStyle != null && !closing) {
                        val closeTag = src.indexOf("</$name", end)
                        val closeEnd = if (closeTag > end) tagEnd(closeTag) else -1
                        if (closeEnd > 0) {
                            flush()
                            appendInline(src.substring(end + 1, closeTag), out, base.merge(pairStyle), codeStyle, linkColor)
                            i = closeEnd + 1
                            continue
                        }
                    }
                    flush()
                    if (name == "br") out.append('\n')
                    i = end + 1
                    continue
                }
            }

            // inline math: show the expression, not the $ / \( delimiters
            if (c == '$' || (c == '\\' && i + 1 < src.length && (src[i + 1] == '(' || src[i + 1] == '['))) {
                val open = if (c == '$') runLength(src, i, '$').coerceAtMost(2) else 2
                val opener = src.substring(i, i + open)
                val closer = when (opener) {
                    "\$" -> "\$"
                    "\$\$" -> "\$\$"
                    "\\(" -> "\\)"
                    else -> "\\]"
                }
                val close = src.indexOf(closer, i + open)
                val inner = if (close > i + open) src.substring(i + open, close).trim() else ""
                if (inner.isNotEmpty() && !inner.contains('\n')) {
                    flush()
                    out.pushStyle(base.merge(SpanStyle(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic)))
                    out.append(inner)
                    out.pop()
                    i = close + closer.length
                    continue
                }
            }

            // emphasis
            if (c == '*' || c == '_') {
                val match = if (c == '*') matchStar(src, i) else matchUnderscore(src, i)
                if (match != null) {
                    flush()
                    appendInline(match.inner, out, base.merge(match.style), codeStyle, linkColor)
                    i = match.end
                    continue
                }
                literal.append(c)
                i++
                continue
            }

            // strikethrough
            if (c == '~' && i + 1 < src.length && src[i + 1] == '~') {
                val close = src.indexOf("~~", i + 2)
                if (close > i + 2) {
                    flush()
                    appendInline(
                        src.substring(i + 2, close), out,
                        base.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)),
                        codeStyle, linkColor
                    )
                    i = close + 2
                } else {
                    literal.append("~~")
                    i += 2
                }
                continue
            }

            // [label](url)
            if (c == '[') {
                val close = src.indexOf(']', i + 1)
                if (close > i + 1 && close + 1 < src.length && src[close + 1] == '(') {
                    val end = src.indexOf(')', close + 2)
                    if (end > close + 2) {
                        flush()
                        val label = src.substring(i + 1, close)
                        var url = src.substring(close + 2, end).trim()
                        if (url.startsWith("<") && url.endsWith(">")) url = url.substring(1, url.length - 1)
                        val target = url.substringBefore(' ').trim()
                        out.pushStyle(base.merge(linkStyle(linkColor)))
                        if (target.isEmpty()) {
                            out.append(label)
                        } else {
                            out.withLink(LinkAnnotation.Url(target)) { append(label) }
                        }
                        out.pop()
                        i = end + 1
                        continue
                    }
                }
            }

            // bare urls
            if (c == 'h' &&
                (src.startsWith("https://", i) || src.startsWith("http://", i)) &&
                (i == 0 || !src[i - 1].isLetterOrDigit())
            ) {
                var end = i
                while (end < src.length && !src[end].isWhitespace() && src[end] != '<' && src[end] != '>' && src[end] != '"') end++
                while (end > i && src[end - 1] in ".,;:!?)]}*_") end--
                if (end > i) {
                    val url = src.substring(i, end)
                    flush()
                    out.pushStyle(base.merge(linkStyle(linkColor)))
                    out.withLink(LinkAnnotation.Url(url)) { append(url) }
                    out.pop()
                    i = end
                    continue
                }
            }

            literal.append(c)
            i++
        }
        flush()
    }

    private fun linkStyle(linkColor: Color) =
        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)

    private data class Emphasis(val end: Int, val inner: String, val style: SpanStyle)

    /** Matches `*emphasis*`, `**strong**` or `***both***` starting at [at]. */
    private fun matchStar(src: String, at: Int): Emphasis? {
        val available = runLength(src, at, '*')
        for (len in minOf(3, available) downTo 1) {
            val close = findRun(src, at + len, '*', len)
            if (close <= at + len) continue
            val inner = src.substring(at + len, close)
            if (inner.isBlank()) continue
            val style = when (len) {
                1 -> SpanStyle(fontStyle = FontStyle.Italic)
                2 -> SpanStyle(fontWeight = FontWeight.Bold)
                else -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
            }
            return Emphasis(close + len, inner, style)
        }
        return null
    }

    /** `_emphasis_` is only honoured at word boundaries so snake_case survives. */
    private fun matchUnderscore(src: String, at: Int): Emphasis? {
        val before = if (at == 0) ' ' else src[at - 1]
        if (before.isLetterOrDigit() || before == '_') return null
        val run = runLength(src, at, '_')
        val len = if (run >= 2) 2 else 1
        val close = findRun(src, at + len, '_', len)
        if (close <= at + len) return null
        val after = if (close + len < src.length) src[close + len] else ' '
        if (after.isLetterOrDigit() || after == '_') return null
        val inner = src.substring(at + len, close)
        if (inner.isBlank()) return null
        val style = if (len == 2) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontStyle = FontStyle.Italic)
        return Emphasis(close + len, inner, style)
    }

    private fun runLength(s: String, from: Int, ch: Char): Int {
        var n = 0
        while (from + n < s.length && s[from + n] == ch) n++
        return n
    }

    private fun findRun(s: String, from: Int, ch: Char, len: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == ch && runLength(s, i, ch) >= len && (i == 0 || s[i - 1] != '\\')) return i
            i++
        }
        return -1
    }
}
