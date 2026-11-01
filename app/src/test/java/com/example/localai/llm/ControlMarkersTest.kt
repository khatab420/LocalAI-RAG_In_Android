package com.example.localai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMarkersTest {

    @Test
    fun removesChatmlMarkers() {
        assertEquals("Hello there", ControlMarkers.clean("Hello there<|im_end|>"))
        assertEquals("one two", ControlMarkers.clean("one<|im_start|>assistant two"))
        assertEquals("Done.", ControlMarkers.clean("<|end|>Done."))
    }

    @Test
    fun removesGemmaAndLlamaMarkers() {
        assertEquals("Sure.", ControlMarkers.clean("Sure.<end_of_turn>"))
        assertEquals("Sure.", ControlMarkers.clean("Sure.</s>"))
        assertEquals("Sure.", ControlMarkers.clean("Sure.<|eot_id|>"))
        assertEquals("Sure.", ControlMarkers.clean("Sure.<|endoftext|>"))
    }

    @Test
    fun removesFullWidthDeepSeekMarkers() {
        assertEquals("巴黎是法国的首都。", ControlMarkers.clean("巴黎是法国的首都。\n\n" +
            "\uFF5Cend\u2581of\u2581sentence\uFF5C", trim = true))
        assertEquals("Answer", ControlMarkers.clean("Answer\uFF1C\uFF5Cend\u2581of\u2581sentence\uFF5C\uFF1E"))
    }

    @Test
    fun leavesOrdinaryAngleBracketsAlone() {
        assertEquals("if a < b then c", ControlMarkers.clean("if a < b then c"))
        assertEquals("List<String>", ControlMarkers.clean("List<String>"))
        assertEquals("x <3", ControlMarkers.clean("x <3"))
        assertEquals("a <b> c", ControlMarkers.clean("a <b> c"))
    }

    @Test
    fun streamHidesMarkerSplitAcrossPieces() {
        val c = StreamingCleaner()
        val out = StringBuilder()
        listOf("Hello", " world", "<|im_", "end", "|>", " and more rambling")
            .forEach { out.append(c.accept(it)) }
        out.append(c.finish())
        assertEquals("Hello world", out.toString())
        assertTrue(c.hitTerminator)
        assertEquals("", c.accept("still talking"))
    }

    @Test
    fun streamHidesGemmaMarkerSplitAcrossPieces() {
        val c = StreamingCleaner()
        val out = StringBuilder()
        listOf("The answer is 4.<end", "_of_turn>", "junk")
            .forEach { out.append(c.accept(it)) }
        out.append(c.finish())
        assertEquals("The answer is 4.", out.toString())
        assertTrue(c.hitTerminator)
    }

    @Test
    fun streamKeepsPlainTextThatOnlyLooksLikeAMarker() {
        val c = StreamingCleaner()
        val out = StringBuilder()
        listOf("Compare a <", "b and c >", " d.").forEach { out.append(c.accept(it)) }
        out.append(c.finish())
        assertEquals("Compare a <b and c > d.", out.toString())
        assertFalse(c.hitTerminator)
    }

    @Test
    fun streamLeavesNoSymbolsAtTheEnd() {
        val c = StreamingCleaner()
        val out = StringBuilder()
        // a marker cut off right before the stream died must not leak its handled text
        listOf("Done.<|im_e").forEach { out.append(c.accept(it)) }
        out.append(c.finish())
        assertEquals("Done.", out.toString())
    }

    @Test
    fun terminatorDetectionIsNotFooledByLongerMarkers() {
        val c = StreamingCleaner()
        c.accept("hi<|start_header_id|>")
        assertFalse("role markers are not the end of a turn", c.hitTerminator)
    }
}
