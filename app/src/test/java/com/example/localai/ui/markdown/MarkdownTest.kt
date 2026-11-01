package com.example.localai.ui.markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `heading levels are recognised`() {
        val blocks = Markdown.parse("# One\n\n### Three\n\ntext")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "One"),
                MdBlock.Heading(3, "Three"),
                MdBlock.Paragraph("text")
            ),
            blocks
        )
    }

    @Test
    fun `fenced code keeps its body and language`() {
        val blocks = Markdown.parse("intro\n\n```kotlin\nval x = 1\nprintln(x)\n```\n\noutro")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\nprintln(x)", code.code)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `unterminated fence still renders as code while streaming`() {
        val blocks = Markdown.parse("Here you go:\n```python\nfor i in range(3):\n    print(i)")
        val code = blocks.last() as MdBlock.Code
        assertEquals("python", code.language)
        assertTrue(code.code.startsWith("for i in range(3):"))
    }

    @Test
    fun `lists keep numbers and nesting`() {
        val ordered = Markdown.parse("3. third\n4. fourth").single() as MdBlock.Numbered
        assertEquals(3, ordered.start)
        assertEquals(listOf("third", "fourth"), ordered.items.map { it.text })

        val bullets = Markdown.parse("- one\n  - nested\n- two").single() as MdBlock.Bullet
        assertEquals(listOf("one", "nested", "two"), bullets.items.map { it.text })
        assertEquals(1, bullets.items[1].level)
    }

    @Test
    fun `tables are split into cells`() {
        val table = Markdown.parse("| Name | Value |\n| --- | --- |\n| a | 1 |\n| b | 2 |")
            .single() as MdBlock.Table
        assertEquals(listOf("Name", "Value"), table.header)
        assertEquals(listOf(listOf("a", "1"), listOf("b", "2")), table.rows)
    }

    @Test
    fun `inline emphasis code and links are styled`() {
        val text = Markdown.inline("**bold** and *italic* and `code` and ~~gone~~")
        assertEquals("bold and italic and code and gone", text.text)

        val styles = text.spanStyles
        assertTrue(styles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(styles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
        assertTrue(styles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(styles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `snake case identifiers survive emphasis parsing`() {
        val text = Markdown.inline("set user_name_here and _emphasis_ ok")
        assertEquals("set user_name_here and emphasis ok", text.text)
        val italic = text.spanStyles.filter { it.item.fontStyle != null }
        assertEquals(1, italic.size)
        assertEquals("emphasis", text.text.substring(italic.single().start, italic.single().end))
    }

    @Test
    fun `streaming partial emphasis does not crash or drop text`() {
        val text = Markdown.inline("This is **still being gener")
        assertEquals("This is **still being gener", text.text)

        val blocks = Markdown.parse("```\nunclosed code")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Code)
    }

    @Test
    fun `quote and rule blocks`() {
        val blocks = Markdown.parse("> quoted **text**\n\n---")
        val quote = blocks[0] as MdBlock.Quote
        assertEquals(MdBlock.Paragraph("quoted **text**"), quote.blocks.single())
        assertEquals(MdBlock.Rule, blocks[1])
    }

    @Test
    fun `blank line separates paragraphs and single newline does not`() {
        val blocks = Markdown.parse("line one\nline two\n\nsecond paragraph")
        assertEquals(2, blocks.size)
        assertEquals("line one\nline two", (blocks[0] as MdBlock.Paragraph).text)
    }

    @Test
    fun `markers without closing pairs stay literal`() {
        val text = Markdown.inline("2 * 3 = 6 and a_b")
        assertEquals("2 * 3 = 6 and a_b", text.text)
        assertFalse(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `a realistic answer keeps all of its text`() {
        val answer = """
            ## Quick answer

            Use **coroutines**, not threads, for this.

            1. Add the dependency
            2. Call `viewModelScope.launch { }`

            ```kotlin
            fun main() = runBlocking {
                launch { println("hi") }   // prints hi
            }
            ```

            > Rule of thumb: keep one dispatcher per device worker.

            | Option | Speed |
            | --- | --- |
            | A | fast |
        """.trimIndent()

        val blocks = Markdown.parse(answer)
        assertTrue(blocks.any { it is MdBlock.Heading })
        assertTrue(blocks.any { it is MdBlock.Numbered })
        assertTrue(blocks.any { it is MdBlock.Code })
        assertTrue(blocks.any { it is MdBlock.Quote })
        assertTrue(blocks.any { it is MdBlock.Table })

        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("fun main() = runBlocking {"))
        assertFalse(code.code.endsWith("\n"))

        val ordered = blocks.filterIsInstance<MdBlock.Numbered>().single()
        assertEquals(1, ordered.start)
        assertEquals(2, ordered.items.size)
    }

    @Test
    fun `pathological input does not loop or crash`() {
        listOf("***", "~~~~", "```", "[a](", "**", "#", "| a |\n|---|", ">", "- ", "1. ").forEach {
            Markdown.parse(it)
            Markdown.inline(it)
        }
    }
}
