package com.example.localai.llm

/** Called from native code for every chunk of generated UTF-8 bytes. Return false to stop. */
fun interface TokenCallback {
    fun onToken(bytes: ByteArray): Boolean
}

/** Thin JNI wrapper. Use [LlamaEngine] instead of calling this directly. */
class LlamaBridge {
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        callback: TokenCallback
    ): Int
    external fun nativeStop(handle: Long)
    external fun nativeUnload(handle: Long)

    // embeddings (RAG)
    external fun nativeEmbedLoad(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeEmbedDim(handle: Long): Int
    external fun nativeEmbed(handle: Long, text: String): FloatArray?
    external fun nativeEmbedUnload(handle: Long)

    companion object {
        init {
            System.loadLibrary("localai")
        }
    }
}
