package com.example.localai.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Whatever the renderer shows must be prose, not markup: a stray "**" or "###" in the
 * answer is what makes an otherwise good looking chat look broken.
 */
class VisibleTextTest {

    private fun visible(source: String): String {
        val out = StringBuilder()
        fun walk(blocks: List<MdBlock>) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> out.append(Markdown.inline(block.text).text).append('\n')
                    is MdBlock.Paragraph -> out.append(Markdown.inline(block.text).text).append('\n')
                    is MdBlock.Bullet -> block.items.forEach {
                        out.append(Markdown.inline(it.text).text).append('\n')
                    }
                    is MdBlock.Numbered -> block.items.forEach {
                        out.append(Markdown.inline(it.text).text).append('\n')
                    }
                    is MdBlock.Quote -> walk(block.blocks)
                    is MdBlock.Table -> {
                        block.header.forEach { out.append(Markdown.inline(it).text).append('\n') }
                        block.rows.forEach { row ->
                            row.forEach { cell -> out.append(Markdown.inline(cell).text).append('\n') }
                        }
                    }
                    is MdBlock.Code -> out.append(block.code).append('\n')
                    MdBlock.Rule -> {}
                }
            }
        }
        walk(Markdown.parse(source))
        return out.toString()
    }

    @Test
    fun `a typical answer shows no markup characters`() {
        val answer = """
            ## Summary

            Kotlin's **coroutines** are *lightweight* threads and ~~not~~ cheap to launch.

            ### Steps

            1. Add the **dependency**
            2. Wrap the call in `runBlocking`
               - nested detail with __bold__ text
            3. Measure with `System.nanoTime()`

            > Note: `Dispatchers.IO` is for blocking work.

            | Option | Cost |
            | --- | --- |
            | `launch` | small |
            | `async` | small |

            ```kotlin
            fun main() = runBlocking {
                println("hi")
            }
            ```

            See [the docs](https://kotlinlang.org) for more.
        """.trimIndent()

        val text = visible(answer)
        listOf("**", "__", "##", "~~", "`", "| --- |", "```").forEach { marker ->
            assertFalse("visible text still contains $marker:\n$text", text.contains(marker))
        }
    }

    @Test
    fun `emphasis variants are rendered not printed`() {
        assertEquals("bold", Markdown.inline("__bold__").text)
        assertEquals("both", Markdown.inline("***both***").text)
        assertEquals("it", Markdown.inline("_it_").text)
        assertEquals("gone", Markdown.inline("~~gone~~").text)
    }

    @Test
    fun `html-ish break tags do not show up as text`() {
        assertEquals("first\nsecond", Markdown.inline("first<br>second").text)
        assertEquals("bold", Markdown.inline("<b>bold</b>").text)
    }

    @Test
    fun `setext heading underline is not printed`() {
        assertEquals("Title", visible("Title\n=====").trim())
    }
}
