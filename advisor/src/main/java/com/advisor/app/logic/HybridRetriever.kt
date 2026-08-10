package com.advisor.app.logic

/**
 * The retrieval strategy the repository holds. It owns the [Embedder] and the [VectorCache] (so the
 * cache survives across questions), and for each question picks how to find the relevant chunks:
 *
 * - when an embedding model is loaded, it uses [EmbeddingRetriever] — semantic ranking, which bridges
 *   the vocabulary gap ("Who am I?" → a `Name:` fact) that used to need special-casing in the engine;
 * - otherwise it falls back to the deterministic, dependency-free lexical [Retriever].
 *
 * This mirrors the generation side exactly (`Qwen3LlmEngine` → `PlaceholderLlmEngine`): the better
 * path activates the moment its model file is present, and the pipeline works — and stays fully
 * JVM-testable — either way. The corpus is passed in per question (already permission-filtered by the
 * repository), so revoking an app takes effect on the very next call with nothing stale consulted.
 */
class HybridRetriever(
    private val embedder: Embedder = Embedder.NONE,
    private val cache: VectorCache = VectorCache.inMemory()
) {

    /** True when semantic retrieval is active; false when running on the lexical fallback. */
    val isSemantic: Boolean get() = embedder.isReady

    fun retrieve(
        corpus: List<KnowledgeDocument>,
        query: String,
        topK: Int = Retriever.DEFAULT_TOP_K
    ): List<RetrievedChunk> =
        if (embedder.isReady) {
            EmbeddingRetriever(corpus, embedder, cache).retrieve(query, topK)
        } else {
            Retriever(corpus).retrieve(query, topK)
        }
}
