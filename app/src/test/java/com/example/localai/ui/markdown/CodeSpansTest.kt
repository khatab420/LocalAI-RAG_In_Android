package com.example.localai.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeSpansTest {

    private fun kinds(code: String) = codeSpans(code).map { it.kind }

    @Test
    fun `strings comments numbers and keywords are found`() {
        val code = "fun greet() {\n  // say hi\n  val who = \"world\"\n  return who + 42\n}"
        val spans = codeSpans(code)
        assertTrue(spans.any { it.kind == CodeTokenKind.Keyword && code.substring(it.start, it.end) == "fun" })
        assertTrue(spans.any { it.kind == CodeTokenKind.Number && code.substring(it.start, it.end) == "42" })
        assertTrue(spans.any { it.kind == CodeTokenKind.String && code.substring(it.start, it.end) == "\"world\"" })
        assertTrue(spans.any { it.kind == CodeTokenKind.Comment && code.substring(it.start, it.end) == "// say hi" })
    }

    @Test
    fun `python hash comments and block comments`() {
        assertTrue(kinds("# not a keyword").contains(CodeTokenKind.Comment))
        assertTrue(kinds("/* multi\nline */ x").contains(CodeTokenKind.Comment))
    }

    @Test
    fun `spans stay inside the text and never overlap`() {
        val code = "const s = \"a b\" // tail\nclass A {}"
        val spans = codeSpans(code).sortedBy { it.start }
        var lastEnd = 0
        spans.forEach { span ->
            assertTrue(span.start >= lastEnd)
            assertTrue(span.end <= code.length)
            assertFalse(span.start == span.end)
            lastEnd = span.end
        }
    }

    @Test
    fun `prose without code markers yields no tokens`() {
        assertEquals(emptyList<CodeSpan>(), codeSpans("just some words here"))
    }
}
