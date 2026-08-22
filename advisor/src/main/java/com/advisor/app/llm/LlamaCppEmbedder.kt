package com.advisor.app.llm

import android.util.Log
import com.advisor.app.logic.Embedder
import com.advisor.app.logic.EmbeddingMath

/**
 * An [Embedder] backed by llama.cpp's native embedding path, loaded over JNI — the vector analogue of
 * [LlamaCppBackend]. It loads whatever embedding GGUF [EmbeddingModelStore] reports as installed
 * through the same `advisor-llm` native library and turns text into a pooled, L2-normalized vector,
 * all on-device with no network.
 *
 * Every native entry point is **guarded**, exactly like the generation backend: if the `.so` or the
 * embedding `.gguf` isn't present, or a native call throws, [isReady] is `false` / [embed] returns an
 * empty vector, and [com.advisor.app.logic.HybridRetriever] falls back to the deterministic lexical
 * retriever. Importing an embedding model activates semantic retrieval on the next question with no
 * restart and no code change.
 */
class LlamaCppEmbedder(private val modelStore: EmbeddingModelStore) : Embedder {

    @Volatile private var handle: Long = 0L
    @Volatile private var dims: Int = 0
    @Volatile private var failedSignature: String? = null
    private val lock = Any()

    override val isReady: Boolean
        get() {
            if (!NATIVE_AVAILABLE) return false
            if (handle != 0L) return true
            val file = modelStore.installedModel() ?: return false
            return signature(file.absolutePath, file.length()) != failedSignature
        }

    /** Filename + size + dimensions so vectors from a different model file invalidate the cache. */
    override val id: String
        get() {
            val file = modelStore.installedModel() ?: return "none"
            return "${file.name}:${file.length()}:$dims"
        }

    override fun embed(text: String): FloatArray {
        if (!ensureLoaded()) return EMPTY
        return runCatching {
            val raw = nativeEmbed(handle, text)
            if (raw.isEmpty()) EMPTY else EmbeddingMath.normalize(raw)
        }.getOrElse {
            Log.w(TAG, "Embedding failed; falling back to lexical retrieval.", it)
            EMPTY
        }
    }

    /**
     * Embed many texts in as few passes over the model as it allows.
     *
     * The default implementation of this is one [embed] per text, and each of those is a full sweep
     * over the embedding model's weights — so indexing a corpus of a few hundred documents paid for a
     * few hundred sweeps. The native side packs several documents into each `llama_decode` instead.
     * That matters most exactly when it is most visible: the vector cache is in-memory, so the whole
     * corpus is embedded again on the first question after every app start.
     *
     * A document that fails comes back empty rather than taking the batch with it, which is what lets
     * [com.advisor.app.logic.EmbeddingRetriever] cache what worked and retry the rest next question.
     */
    override fun embedAll(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        if (!ensureLoaded()) return texts.map { EMPTY }
        return runCatching {
            val raw = nativeEmbedAll(handle, texts.toTypedArray())
            texts.indices.map { i ->
                val vector = raw.getOrNull(i)
                if (vector == null || vector.isEmpty()) EMPTY else EmbeddingMath.normalize(vector)
            }
        }.getOrElse {
            Log.w(TAG, "Batch embedding failed; falling back to lexical retrieval.", it)
            texts.map { EMPTY }
        }
    }

    fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                runCatching { nativeFree(handle) }
                handle = 0L
            }
        }
    }

    /** Load the embedding model on first use; a file that already failed is skipped until it changes. */
    private fun ensureLoaded(): Boolean {
        if (handle != 0L) return true
        if (!NATIVE_AVAILABLE) return false
        val file = modelStore.installedModel() ?: return false
        val sig = signature(file.absolutePath, file.length())
        if (sig == failedSignature) return false
        synchronized(lock) {
            if (handle != 0L) return true
            handle = runCatching { nativeLoad(file.absolutePath) }.getOrElse {
                Log.w(TAG, "Failed to load embedding GGUF at ${file.absolutePath}", it)
                0L
            }
            if (handle == 0L) {
                failedSignature = sig
                return false
            }
            dims = runCatching { nativeDim(handle) }.getOrDefault(0)
            failedSignature = null
            return true
        }
    }

    private fun signature(path: String, size: Long): String = "$path:$size"

    // --- JNI: implemented by the `advisor-llm` native library (llama.cpp embedding path) ---

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeDim(handle: Long): Int
    private external fun nativeEmbed(handle: Long, text: String): FloatArray
    private external fun nativeEmbedAll(handle: Long, texts: Array<String>): Array<FloatArray?>
    private external fun nativeFree(handle: Long)

    companion object {
        private const val TAG = "LlamaCppEmbedder"
        private val EMPTY = FloatArray(0)

        /** True once the native library is present; shared with [LlamaCppBackend] (same `.so`). */
        private val NATIVE_AVAILABLE: Boolean = runCatching {
            System.loadLibrary("advisor-llm")
            true
        }.getOrElse {
            Log.i(TAG, "Native llama.cpp backend not present; Advisor will use lexical retrieval.")
            false
        }
    }
}
