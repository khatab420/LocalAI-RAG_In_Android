package com.example.localai.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Renders model output the way people expect from a chat assistant: real headings,
 * wrapped paragraphs, bullet and numbered lists, quotes, tables and code blocks with
 * their own header, horizontal scrolling and a copy button.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    caretAlpha: Float? = null,
    codeFontSize: TextUnit = 13.sp
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    val colors = MaterialTheme.colorScheme
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = colors.surfaceContainerHighest,
        color = colors.onSurface
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            MarkdownBlockView(
                block = block,
                body = style,
                caretAlpha = if (index == blocks.lastIndex) caretAlpha else null,
                caretColor = colors.primary,
                codeStyle = codeStyle,
                linkColor = colors.primary,
                codeFontSize = codeFontSize
            )
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MdBlock,
    body: TextStyle,
    caretAlpha: Float?,
    caretColor: Color,
    codeStyle: SpanStyle,
    linkColor: Color,
    codeFontSize: TextUnit
) {
    when (block) {
        is MdBlock.Heading -> {
            val headingStyle = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                text = Markdown.inline(block.text, codeStyle, linkColor),
                style = headingStyle.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )
        }

        is MdBlock.Paragraph -> Text(
            text = inlineWithCaret(block.text, caretAlpha, codeStyle, linkColor, caretColor),
            style = body,
            modifier = Modifier.fillMaxWidth()
        )

        is MdBlock.Code -> CodeBlock(block, codeFontSize)

        is MdBlock.Bullet -> BulletList(block.items, body, caretAlpha, caretColor, codeStyle, linkColor)

        is MdBlock.Numbered -> NumberedList(block, body, caretAlpha, caretColor, codeStyle, linkColor)

        is MdBlock.Quote -> Quote(block.blocks, body, codeStyle, linkColor)

        is MdBlock.Table -> Table(block, body, codeStyle, linkColor)

        MdBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun BulletList(
    items: List<MdItem>,
    body: TextStyle,
    caretAlpha: Float?,
    caretColor: Color,
    codeStyle: SpanStyle,
    linkColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { index, item ->
            ListRow(
                marker = if (item.level == 0) "•" else "◦",
                item = item,
                markerWidth = 16.dp,
                body = body,
                caretAlpha = if (index == items.lastIndex) caretAlpha else null,
                caretColor = caretColor,
                codeStyle = codeStyle,
                linkColor = linkColor
            )
        }
    }
}

@Composable
private fun NumberedList(
    block: MdBlock.Numbered,
    body: TextStyle,
    caretAlpha: Float?,
    caretColor: Color,
    codeStyle: SpanStyle,
    linkColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        block.items.forEachIndexed { index, item ->
            ListRow(
                marker = "${block.start + index}.",
                item = item,
                markerWidth = 24.dp,
                body = body,
                caretAlpha = if (index == block.items.lastIndex) caretAlpha else null,
                caretColor = caretColor,
                codeStyle = codeStyle,
                linkColor = linkColor
            )
        }
    }
}

@Composable
private fun ListRow(
    marker: String,
    item: MdItem,
    markerWidth: Dp,
    body: TextStyle,
    caretAlpha: Float?,
    caretColor: Color,
    codeStyle: SpanStyle,
    linkColor: Color
) {
    Row(Modifier.padding(start = (item.level * 14).dp)) {
        Text(
            text = marker,
            style = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.width(markerWidth)
        )
        Text(
            text = inlineWithCaret(item.text, caretAlpha, codeStyle, linkColor, caretColor),
            style = body,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Quote(blocks: List<MdBlock>, body: TextStyle, codeStyle: SpanStyle, linkColor: Color) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(top = 2.dp, bottom = 2.dp)
                .width(3.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
        )
        Column(
            Modifier
                .padding(start = 12.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val quoted = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            blocks.forEach { inner ->
                MarkdownBlockView(
                    block = inner,
                    body = quoted,
                    caretAlpha = null,
                    caretColor = linkColor,
                    codeStyle = codeStyle,
                    linkColor = linkColor,
                    codeFontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun Table(block: MdBlock.Table, body: TextStyle, codeStyle: SpanStyle, linkColor: Color) {
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val cellStyle = body.copy(fontSize = 14.sp, lineHeight = 20.sp)

    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            repeat(columns) { column ->
                TableCell(
                    text = block.header.getOrNull(column).orEmpty(),
                    style = cellStyle.copy(fontWeight = FontWeight.SemiBold),
                    header = true,
                    codeStyle = codeStyle,
                    linkColor = linkColor
                )
            }
        }
        block.rows.forEachIndexed { rowIndex, row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.background(
                    if (rowIndex % 2 == 1) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
                )
            ) {
                repeat(columns) { column ->
                    TableCell(
                        text = row.getOrNull(column).orEmpty(),
                        style = cellStyle,
                        header = false,
                        codeStyle = codeStyle,
                        linkColor = linkColor
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    style: TextStyle,
    header: Boolean,
    codeStyle: SpanStyle,
    linkColor: Color
) {
    Box(
        Modifier
            .widthIn(min = 88.dp, max = 240.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = Markdown.inline(text, codeStyle, linkColor),
            style = style,
            textAlign = TextAlign.Start,
            maxLines = if (header) 2 else Int.MAX_VALUE
        )
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, fontSize: TextUnit) {
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    val language = block.language.lowercase().ifBlank { "code" }
    val shape = RoundedCornerShape(12.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, colors.outlineVariant, shape)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceContainerHighest)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(block.code))
                    copied = true
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (copied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = colors.outlineVariant)

        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = highlightCode(block.code, language),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.45f,
                    color = colors.onSurface
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Syntax highlighting: good enough tokens for the languages models usually answer in.
// ---------------------------------------------------------------------------------------------

private const val KEYWORDS =
    "abstract|actual|and|as|async|await|break|case|catch|class|companion|const|constructor|continue|" +
        "data|def|defer|do|dynamic|elif|else|enum|except|export|extends|external|false|final|finally|" +
        "fn|for|from|fun|function|global|go|if|impl|implements|import|in|init|instanceof|interface|" +
        "internal|is|lambda|lateinit|let|loop|match|mod|mut|new|nil|none|null|object|open|operator|" +
        "or|override|package|pass|print|private|protected|pub|public|raise|readonly|record|ref|" +
        "return|self|sealed|static|struct|super|switch|then|this|throw|trait|true|try|type|typeof|" +
        "use|val|var|void|when|where|while|with|yield"

private val CODE_TOKEN = Regex(
    "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')" +   // 1: strings
        "|(//[^\\n]*|#[^\\n]*)" +                             // 2: line comments
        "|(/\\*[\\s\\S]*?\\*/)" +                             // 3: block comments
        "|(\\b\\d+(?:\\.\\d+)?\\b)" +                          // 4: numbers
        "|(\\b(?:" + KEYWORDS + ")\\b)",                       // 5: keywords
    RegexOption.IGNORE_CASE
)

internal enum class CodeTokenKind { String, Comment, Number, Keyword }

internal data class CodeSpan(val kind: CodeTokenKind, val start: Int, val end: Int)

/** Tokenises a code block into coloured ranges; kept pure so it can be unit tested. */
internal fun codeSpans(code: String): List<CodeSpan> =
    CODE_TOKEN.findAll(code).map { match ->
        val kind = when {
            match.groups[1] != null -> CodeTokenKind.String
            match.groups[2] != null || match.groups[3] != null -> CodeTokenKind.Comment
            match.groups[4] != null -> CodeTokenKind.Number
            else -> CodeTokenKind.Keyword
        }
        CodeSpan(kind, match.range.first, match.range.last + 1)
    }.toList()

@Composable
private fun highlightCode(code: String, language: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(code, language, colors.primary, colors.tertiary, colors.secondary, colors.onSurfaceVariant) {
        buildAnnotatedString {
            append(code)
            codeSpans(code).forEach { span ->
                val color = when (span.kind) {
                    CodeTokenKind.String -> colors.tertiary
                    CodeTokenKind.Comment -> colors.onSurfaceVariant
                    CodeTokenKind.Number -> colors.secondary
                    CodeTokenKind.Keyword -> colors.primary
                }
                val italic = span.kind == CodeTokenKind.Comment
                addStyle(
                    SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null),
                    span.start,
                    span.end
                )
            }
        }
    }
}

private fun inlineWithCaret(
    text: String,
    caretAlpha: Float?,
    codeStyle: SpanStyle,
    linkColor: Color,
    caretColor: Color
): AnnotatedString {
    val annotated = Markdown.inline(text, codeStyle, linkColor)
    if (caretAlpha == null) return annotated
    return buildAnnotatedString {
        append(annotated)
        withStyle(SpanStyle(color = caretColor.copy(alpha = caretAlpha.coerceIn(0f, 1f)))) {
            append("\u258D")
        }
    }
}
