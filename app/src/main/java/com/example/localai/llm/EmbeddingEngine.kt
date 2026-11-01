package com.example.localai.llm

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Loads a small embedding model (e.g. bge-small) and turns text into vectors. */
class EmbeddingEngine {
    private val bridge = LlamaBridge()
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "embed-worker") }
        .asCoroutineDispatcher()

    @Volatile
    private var handle = 0L

    @Volatile
    var loadedName: String? = null
        private set

    @Volatile
    var dim: Int = 0
        private set

    val isLoaded: Boolean get() = handle != 0L

    suspend fun load(path: String, name: String): Boolean = withContext(dispatcher) {
        if (handle != 0L) {
            bridge.nativeEmbedUnload(handle)
            handle = 0L; loadedName = null; dim = 0
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
        val h = bridge.nativeEmbedLoad(path, 512, threads)
        handle = h
        if (h != 0L) {
            loadedName = name
            dim = bridge.nativeEmbedDim(h)
        }
        h != 0L
    }

    suspend fun unload() = withContext(dispatcher) {
        if (handle != 0L) {
            bridge.nativeEmbedUnload(handle)
            handle = 0L; loadedName = null; dim = 0
        }
    }

    /** Raw (not normalized) embedding, or null if no model is loaded / failure. */
    suspend fun embed(text: String): FloatArray? = withContext(dispatcher) {
        val h = handle
        if (h == 0L) null else bridge.nativeEmbed(h, text)
    }
}
