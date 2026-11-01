package com.example.localai.llm

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ChatMessage(
    val role: String,
    val content: String,
    val sources: List<String> = emptyList()   // RAG sources shown under an answer (UI only)
)

/**
 * Owns the loaded model. All native calls that can block run on one dedicated thread.
 */
class LlamaEngine {
    private val bridge = LlamaBridge()
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-worker") }
        .asCoroutineDispatcher()
    private val lock = Any()

    @Volatile
    private var handle = 0L

    @Volatile
    var loadedName: String? = null
        private set

    val isLoaded: Boolean get() = handle != 0L

    suspend fun load(path: String, name: String, nCtx: Int = 4096): Boolean {
        unload()
        return withContext(dispatcher) {
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
            val h = bridge.nativeLoad(path, nCtx, threads)
            synchronized(lock) {
                handle = h
                loadedName = if (h != 0L) name else null
            }
            h != 0L
        }
    }

    /**
     * Asks the native loop to stop generating, right now. Safe from any thread, and called
     * straight from the Stop button so the tap does not have to wait for coroutine machinery
     * (which runs on the main thread and can be busy while a long answer is being redrawn).
     */
    fun cancelGeneration() {
        synchronized(lock) { if (handle != 0L) bridge.nativeStop(handle) }
    }

    suspend fun unload() {
        cancelGeneration()
        withContext(dispatcher) {
            synchronized(lock) {
                if (handle != 0L) {
                    bridge.nativeUnload(handle)
                    handle = 0L
                    loadedName = null
                }
            }
        }
    }

    /** Streams generated text pieces. Cancelling the collector stops generation. */
    fun generate(messages: List<ChatMessage>, maxTokens: Int = 1024): Flow<String> = callbackFlow {
        val h = handle
        if (h == 0L) throw IllegalStateException("No model loaded")
        val roles = messages.map { it.role }.toTypedArray()
        val contents = messages.map { it.content }.toTypedArray()
        val finished = AtomicBoolean(false)
        // A Stop can land before this coroutine reaches the native call; the native entry
        // point clears the flag, so remember it here and never start in that case.
        val stopped = AtomicBoolean(false)

        launch(dispatcher) {
            if (stopped.get()) {
                close()
                return@launch
            }
            var rc = 0
            try {
                rc = bridge.nativeGenerate(h, roles, contents, maxTokens) { bytes ->
                    trySend(String(bytes, Charsets.UTF_8)).isSuccess
                }
            } finally {
                synchronized(lock) { finished.set(true) }
            }
            when (rc) {
                -2 -> close(IllegalStateException("This chat is too long for the context window. Start a new chat."))
                -3 -> close(IllegalStateException("Could not build the prompt for this model."))
                -1 -> close(IllegalStateException("Model is not loaded."))
                else -> close()
            }
        }

        awaitClose {
            stopped.set(true)
            synchronized(lock) {
                if (!finished.get() && handle == h) bridge.nativeStop(h)
            }
        }
    }.buffer(Channel.UNLIMITED)
}
