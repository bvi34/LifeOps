package com.advisor.app.logic

/**
 * The seam a semantic embedding model runs behind — the vector analogue of [LlmBackend]. An embedder
 * turns text into a fixed-length vector so [EmbeddingRetriever] can rank the corpus by meaning rather
 * than by shared surface words. It is kept framework-free so the retrieval logic and its tests depend
 * only on this interface, never on the JNI / llama.cpp implementation
 * ([com.advisor.app.llm.LlamaCppEmbedder]) that provides it in the app.
 *
 * Just like the generation backend, it is **guarded**: when no embedding model is provisioned
 * [isReady] is `false` and [com.advisor.app.logic.HybridRetriever] falls back to the deterministic
 * lexical [Retriever]. Nothing in the pipeline requires an embedder to be present.
 */
interface Embedder {

    /** True once an embedding model is loaded and [embed] will return real vectors. */
    val isReady: Boolean

    /**
     * A stable identifier for the model (e.g. `"advisor-embed.gguf:384"`). It is part of every cache
     * key, so swapping the embedding model automatically invalidates vectors made by the old one — a
     * vector is only comparable to others from the *same* model.
     */
    val id: String

    /** Embed a single text into a vector. Callers should guard on [isReady] first. */
    fun embed(text: String): FloatArray

    /**
     * Embed [texts] together. Implementations that can should do so in as few passes over the model as
     * possible: this is how the corpus is indexed, and one pass per document is one sweep over the
     * model's weights per document — the dominant cost of the first question after a cold start.
     *
     * Order matches [texts], and a text that could not be embedded comes back as an empty array rather
     * than failing the batch, so a caller can keep what worked and retry the rest.
     *
     * The default is one [embed] per text, which is correct but slow; it exists so a simple embedder
     * needs no batching code.
     */
    fun embedAll(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    companion object {
        /** An embedder that is never ready; [HybridRetriever] then uses the lexical retriever. */
        val NONE: Embedder = object : Embedder {
            override val isReady = false
            override val id = "none"
            override fun embed(text: String): FloatArray = FloatArray(0)
        }
    }
}
