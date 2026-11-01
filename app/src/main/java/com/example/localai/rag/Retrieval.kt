package com.example.localai.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.sqrt

data class Hit(val docName: String, val text: String, val chunkIndex: Int)

object Chunker {
    /** Splits text into ~[size]-character passages that overlap by ~[overlap] characters. */
    fun chunk(raw: String, size: Int = 900, overlap: Int = 150): List<String> {
        val text = raw.replace("\r", "")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (text.isEmpty()) return emptyList()

        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) {
                // prefer to cut at a paragraph / sentence / word boundary
                val window = text.substring(start, end)
                val cut = maxOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf('\n'), window.lastIndexOf(' '))
                if (cut > size / 2) end = start + cut + 1
            }
            val piece = text.substring(start, end).trim()
            if (piece.length > 20) out += piece
            if (end >= text.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return out
    }
}

private val TOKEN = Regex("[\\p{L}\\p{N}]+")

private val STOPWORDS = setOf(
    "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "do", "does", "for", "from",
    "had", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my", "no", "not", "of",
    "on", "or", "our", "so", "than", "that", "the", "their", "them", "then", "there", "these", "they",
    "this", "to", "was", "we", "were", "what", "when", "where", "which", "who", "why", "will", "with",
    "would", "you", "your"
)

/** Lowercase word tokens; drops common English stopwords and strips a plural "s" (refunds -> refund). */
fun tokenize(s: String): List<String> =
    TOKEN.findAll(s.lowercase())
        .map { it.value }
        .filter { it !in STOPWORDS }
        .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
        .toList()

/** In-memory BM25 keyword index. Works for any language that separates words with spaces. */
class Bm25Index(chunks: List<ChunkText>) {
    private class Posting(val doc: Int, val tf: Int)

    private val ids = LongArray(chunks.size)
    private val lens = IntArray(chunks.size)
    private val postings = HashMap<String, ArrayList<Posting>>()
    private val avgLen: Double

    init {
        var total = 0L
        chunks.forEachIndexed { i, c ->
            ids[i] = c.id
            val tokens = tokenize(c.text)
            lens[i] = tokens.size
            total += tokens.size
            val tf = HashMap<String, Int>()
            for (t in tokens) tf.merge(t, 1, Int::plus)
            for ((term, n) in tf) postings.getOrPut(term) { ArrayList() }.add(Posting(i, n))
        }
        avgLen = if (chunks.isEmpty()) 1.0 else maxOf(1.0, total.toDouble() / chunks.size)
    }

    fun search(query: String, k: Int): List<Pair<Long, Double>> {
        val n = ids.size
        if (n == 0) return emptyList()
        val k1 = 1.5
        val b = 0.75
        val scores = HashMap<Int, Double>()
        for (term in tokenize(query).distinct()) {
            val list = postings[term] ?: continue
            val idf = ln(1.0 + (n - list.size + 0.5) / (list.size + 0.5))
            for (p in list) {
                val norm = p.tf + k1 * (1 - b + b * lens[p.doc] / avgLen)
                scores.merge(p.doc, idf * (p.tf * (k1 + 1)) / norm, Double::plus)
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(k).map { ids[it.key] to it.value }
    }
}

/** Brute-force cosine search over normalized vectors (fast enough for tens of thousands of passages). */
class VecIndex(val dim: Int, private val ids: LongArray, private val data: FloatArray) {
    fun search(q: FloatArray, k: Int): List<Pair<Long, Double>> {
        if (ids.isEmpty()) return emptyList()
        val heap = PriorityQueue<Pair<Int, Float>>(k + 1, compareBy<Pair<Int, Float>> { it.second })
        for (i in ids.indices) {
            var dot = 0f
            val off = i * dim
            for (j in 0 until dim) dot += q[j] * data[off + j]
            if (heap.size < k) heap.add(i to dot)
            else if (dot > heap.peek()!!.second) { heap.poll(); heap.add(i to dot) }
        }
        return heap.sortedByDescending { it.second }.map { ids[it.first] to it.second.toDouble() }
    }

    companion object {
        fun build(rows: List<ChunkVec>, dim: Int): VecIndex {
            val valid = rows.filter { it.embedding.size == dim * 4 }
            val ids = LongArray(valid.size)
            val data = FloatArray(valid.size * dim)
            valid.forEachIndexed { i, r ->
                ids[i] = r.id
                ByteBuffer.wrap(r.embedding).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data, i * dim, dim)
            }
            return VecIndex(dim, ids, data)
        }
    }
}

fun FloatArray.l2Normalized(): FloatArray {
    var sum = 0.0
    for (v in this) sum += v * v
    val norm = sqrt(sum).toFloat()
    return if (norm > 0f) FloatArray(size) { this[it] / norm } else this
}

fun FloatArray.toBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.asFloatBuffer().put(this)
    return buf.array()
}

/** Reciprocal-rank fusion of several ranked id lists. */
fun fuse(vararg rankings: List<Pair<Long, Double>>, k: Int = 60): List<Long> {
    val scores = HashMap<Long, Double>()
    for (r in rankings) r.forEachIndexed { i, p -> scores.merge(p.first, 1.0 / (k + i + 1), Double::plus) }
    return scores.entries.sortedByDescending { it.value }.map { it.key }
}

/** Query / passage prefixes some embedding models expect. */
object EmbedProfile {
    fun doc(model: String?): String {
        val n = model?.lowercase() ?: return ""
        return when {
            "nomic" in n -> "search_document: "
            "e5-" in n -> "passage: "
            else -> ""
        }
    }

    fun query(model: String?): String {
        val n = model?.lowercase() ?: return ""
        return when {
            "nomic" in n -> "search_query: "
            "e5-" in n -> "query: "
            "bge" in n -> "Represent this sentence for searching relevant passages: "
            else -> ""
        }
    }
}

object RagPrompt {
    fun build(question: String, hits: List<Hit>): String = buildString {
        appendLine("Answer the question using the context below. If the context does not contain the answer, say so briefly, then answer from your general knowledge.")
        appendLine()
        appendLine("Context:")
        hits.forEachIndexed { i, h ->
            appendLine("[${i + 1}] (${h.docName})")
            appendLine(h.text.trim())
            appendLine()
        }
        append("Question: ").append(question)
    }
}
