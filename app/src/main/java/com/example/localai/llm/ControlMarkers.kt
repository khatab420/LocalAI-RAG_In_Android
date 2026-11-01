package com.example.localai.llm

/**
 * Chat templates wrap turns in markers such as `<|im_end|>`, `<|eot_id|>` or `<end_of_turn>`.
 * When a model keeps generating past its stop token (or the GGUF's template does not match the
 * one baked into the file) those markers end up in the answer and look like garbage on screen.
 * They never belong in the transcript, so they are removed here — including when a marker is
 * split across two streamed pieces.
 */
object ControlMarkers {

    private const val TAG_NAMES =
        "s|bos|eos|unk|pad|start_of_turn|end_of_turn|im_start|im_end|eot_id|start_of_text|" +
            "begin_of_text|end_of_text|tool_call|tool_response|observation|assistant|system|user|" +
            "prompt|response|end|begin"

    internal const val FW_PIPE = '\uFF5C'      // ｜ full-width vertical line
    internal const val FW_LT = '\uFF1C'        // ＜
    internal const val FW_GT = '\uFF1E'        // ＞

    /**
     * Full-width brackets matter too: DeepSeek R1 delimits turns with U+FF5C pipes and writes
     * the sentence marker with no angle brackets at all, so that shape is matched on its own.
     */
    private val MARKER_PATTERN =
        "[<\uFF1C][|\uFF5C][^|\uFF5C<>\uFF1C\uFF1E]{0,64}[|\uFF5C][>\uFF1E]" +   // <|im_start|>  ＜｜end｜＞
            "|\uFF5C[^|\uFF5C<>\uFF1C\uFF1E]{0,64}\uFF5C" +          // ｜end▁of▁sentence｜
            "|[<\uFF1C]/?(?:" + TAG_NAMES + ")\\s*/?[>\uFF1E]" +     // <end_of_turn>  </s>
            "|<\\|>"                                                // leftover pipe pair

    private val MARKER = Regex(MARKER_PATTERN, RegexOption.IGNORE_CASE)

    // `<|im_start|>assistant` is a prompt wrapper, not prose: drop the role word with it.
    private val MARKER_AND_ROLE =
        Regex("(?:$MARKER_PATTERN)\\s*(?:assistant|system|user|model):?", RegexOption.IGNORE_CASE)

    private val SHORT_END = listOf("</s>", "<|eos|>")

    // A model that emits one of its own end-of-turn markers is done talking; anything after it
    // is rambling, so generation is cut short instead of being shown.
    private val END_WORDS = listOf("endof", "end_of", "_end", "eot", "eos")

    private val NAMES = TAG_NAMES.split('|')

    /** The markers found in [text], in order. */
    internal fun markersIn(text: String): List<String> =
        MARKER.findAll(text).map { it.value }.toList()

    /** True when [text] is exactly one marker, e.g. `｜end▁of▁sentence｜`. */
    internal fun isWholeMarker(text: String): Boolean = MARKER.matches(text.trim())

    /** Removes control markers. [trim] is only used for stored/whole messages. */
    fun clean(text: String, trim: Boolean = false): String {
        val cleaned = MARKER.replace(MARKER_AND_ROLE.replace(text, ""), "")
        return if (trim) cleaned.trim() else cleaned
    }

    /** True when this *matched marker* is an end-of-turn one, e.g. `<|im_end|>`. */
    internal fun isTerminator(marker: String): Boolean {
        val raw = marker.lowercase()
            .replace(FW_PIPE, '|').replace(FW_LT, '<').replace(FW_GT, '>')
        if (raw.trim() in SHORT_END) return true
        val body = raw.filter { it.isLetterOrDigit() || it == '_' }
        return body.startsWith("end") || END_WORDS.any { body.contains(it) }
    }

    /** True when [tail] could still turn into a marker once more text streams in. */
    internal fun looksLikeMarkerStart(tail: String): Boolean {
        if (tail.isEmpty()) return true
        if (tail[0] == FW_PIPE) return true                 // ｜end▁of▁sentence｜
        if (tail.length < 2) return true
        if (tail[1] == '|' || tail[1] == FW_PIPE) return true
        val body = tail.drop(1).removePrefix("/")
        val name = body.takeWhile { it.isLetterOrDigit() || it == '_' }.lowercase()
        if (name.isEmpty()) return false
        return NAMES.any { it.startsWith(name) || name.startsWith(it) }
    }

    /** Characters that can begin a marker; the stream holds these back until they resolve. */
    internal fun isMarkerStartChar(c: Char): Boolean = c == '<' || c == FW_LT || c == FW_PIPE
}

/**
 * Strips [ControlMarkers] from a live token stream. A marker can arrive in pieces, so a tail
 * that might still become one is held back until it is complete (usually a single token).
 */
class StreamingCleaner {

    private val held = StringBuilder()

    /** Set when the model emitted its own end-of-turn marker; generation should stop. */
    var hitTerminator: Boolean = false
        private set

    /** Returns the part of [piece] that is safe to show now. */
    fun accept(piece: String): String {
        if (hitTerminator) return ""       // the turn is over; never show what follows
        held.append(piece)
        val cut = safeCut()
        if (cut <= 0) return ""
        val ready = held.substring(0, cut)
        held.delete(0, cut)
        return strip(ready)
    }

    /**
     * Everything still buffered, cleaned. Call once when the stream ends. A buffer that was
     * cut in the middle of a marker (`Done.<|im_e`) is dropped rather than shown.
     */
    fun finish(): String {
        val text = held.toString()
        held.setLength(0)
        if (hitTerminator) return ""
        val held0 = pendingStart(text)
        return strip(if (held0 < 0) text else text.substring(0, held0))
    }

    private fun strip(text: String): String {
        ControlMarkers.markersIn(text).forEach { marker ->
            if (ControlMarkers.isTerminator(marker)) hitTerminator = true
        }
        return ControlMarkers.clean(text)
    }

    private fun safeCut(): Int {
        val at = pendingStart(held.toString())
        return if (at < 0) held.length else at
    }

    /** Index where a possibly unfinished marker begins, or -1 when the text is safe to show. */
    private fun pendingStart(text: String): Int {
        var lt = -1
        // a marker is at most ~70 characters long, so only the tail can still be pending
        val from = maxOf(0, text.length - 120)
        for (i in from until text.length) {
            if (ControlMarkers.isMarkerStartChar(text[i])) lt = i
        }
        if (lt < 0) return -1
        val tail = text.substring(lt)
        if (tail.contains('>') || tail.contains(ControlMarkers.FW_GT)) return -1
        // a finished full-width marker has no '>' to close it; let it through so it is stripped
        if (ControlMarkers.isWholeMarker(tail)) return -1
        if (tail.length > 80) return -1
        return if (ControlMarkers.looksLikeMarkerStart(tail)) lt else -1
    }
}
