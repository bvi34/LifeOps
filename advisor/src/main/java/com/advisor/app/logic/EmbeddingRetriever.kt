package com.advisor.app.logic

/**
 * The semantic **R** in RAG: it ranks the corpus by *meaning* using an [Embedder], instead of by
 * shared surface words like the lexical [Retriever]. This is what lets "Who am I?" find a `Name: …`
 * fact — the two share no literal tokens, but their embeddings are close — so the reasoning engine no
 * longer needs hand-written special cases (identity phrases, per-app keyword lists) to bridge that
 * vocabulary gap. It returns the same [RetrievedChunk]s behind the same `retrieve(...)` shape, so the
 * rest of the pipeline (permissions → retrieve → prompt → model) does not care how the chunks were
 * found.
 *
 * Document vectors are memoized in a [VectorCache] keyed by content + model, so the corpus is embedded
 * once per process, not once per question. Callers use it via [HybridRetriever], which falls back to
 * the lexical retriever whenever no embedding model is loaded — so this class always runs with a
 * ready [embedder].
 */
class EmbeddingRetriever(
    private val documents: List<KnowledgeDocument>,
    private val embedder: Embedder,
    private val cache: VectorCache,
    private val minSimilarity: Double = DEFAULT_MIN_SIMILARITY
) {

    /**
     * The top [topK] documents for [query] by cosine similarity, best first, dropping anything below
     * [minSimilarity] so a clearly-unrelated question returns nothing (the honest "no match" the engine
     * turns into a clarification) rather than a list of weak, misleading hits. Recency breaks ties, as
     * in the lexical retriever.
     */
    fun retrieve(query: String, topK: Int = Retriever.DEFAULT_TOP_K): List<RetrievedChunk> {
        if (query.isBlank() || documents.isEmpty() || !embedder.isReady) return emptyList()

        val queryVec = embedder.embed(query)
        if (queryVec.isEmpty()) return emptyList()

        val docVecs = embedDocuments()

        return documents.indices
            .map { i -> RetrievedChunk(documents[i], EmbeddingMath.cosine(queryVec, docVecs[i])) }
            .filter { it.score >= minSimilarity }
            .sortedWith(
                compareByDescending<RetrievedChunk> { it.score }
                    .thenByDescending { it.document.timestamp }
            )
            .take(topK)
    }

    /** Vectors for every document, served from the cache and computing only the misses in one batch. */
    private fun embedDocuments(): List<FloatArray> {
        val keys = documents.map { VectorKey(it.id, VectorCache.contentHash(it), embedder.id) }
        val cached = keys.map { cache.get(it) }

        val missingIndices = cached.indices.filter { cached[it] == null }
        if (missingIndices.isNotEmpty()) {
            val computed = embedder.embedAll(missingIndices.map { embedText(documents[it]) })
            missingIndices.forEachIndexed { j, docIndex ->
                // Only cache real vectors — a transient embed failure returns empty and must be
                // retried next question, not poisoned into the cache as a permanent miss.
                val vec = computed.getOrNull(j)
                if (vec != null && vec.isNotEmpty()) cache.put(keys[docIndex], vec)
            }
            // One write per round of embedding, not one per document: the whole point of a cache that
            // outlives the process is to make the *next* cold start cheap, and a corpus is embedded in
            // a burst and then read many times.
            cache.flush()
        }

        return keys.map { cache.get(it) ?: EMPTY }
    }

    /** The text an embedding is computed over: title then body, so the title's terms carry weight. */
    private fun embedText(doc: KnowledgeDocument): String =
        if (doc.body.isBlank()) doc.title else "${doc.title}\n${doc.body}"

    companion object {
        /**
         * Cosine floor below which a document is treated as unrelated. Tuned conservatively for a
         * normalized sentence-embedding model; adjust alongside the model. It exists so this retriever
         * keeps the lexical one's "no overlap ⇒ empty" behaviour, which the C3A engine relies on to ask
         * instead of answering when nothing on hand fits.
         */
        const val DEFAULT_MIN_SIMILARITY = 0.25

        private val EMPTY = FloatArray(0)
    }
}
