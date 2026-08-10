package com.advisor.app.logic

import kotlin.math.ln

/**
 * The **R** in RAG: a small, dependency-free lexical retriever. It ranks the corpus against a query
 * with TF-IDF scoring (title terms weighted heavier than body terms) and returns the best few
 * documents as [RetrievedChunk]s. No embeddings, no network, no model — retrieval stays cheap,
 * deterministic and fully testable on the JVM, which is exactly what a small local model needs in
 * front of it so it only ever reasons over a handful of relevant, cited passages.
 *
 * A real embedding index can replace this later behind the same [retrieve] shape; the rest of the
 * pipeline (permissions → retrieve → prompt → model) does not care how the chunks were found.
 */
class Retriever(private val documents: List<KnowledgeDocument>) {

    // Per-document weighted term frequencies, and the corpus-wide inverse document frequency.
    private val termFreqs: List<Map<String, Int>>
    private val idf: Map<String, Double>

    init {
        termFreqs = documents.map { doc ->
            val counts = HashMap<String, Int>()
            for (t in tokenize(doc.title)) counts[t] = (counts[t] ?: 0) + TITLE_WEIGHT
            for (t in tokenize(doc.body)) counts[t] = (counts[t] ?: 0) + 1
            counts
        }
        val n = documents.size
        val docFreq = HashMap<String, Int>()
        for (counts in termFreqs) for (term in counts.keys) docFreq[term] = (docFreq[term] ?: 0) + 1
        // Smoothed idf: rare terms score high, terms in every doc contribute little.
        idf = docFreq.mapValues { (_, df) -> ln((n + 1.0) / (df + 1.0)) + 1.0 }
    }

    /**
     * The top [topK] documents for [query], best first, dropping anything with no term overlap.
     * Ties break toward the more recent document so "what did I do lately" surfaces fresh rows.
     */
    fun retrieve(query: String, topK: Int = DEFAULT_TOP_K): List<RetrievedChunk> {
        val queryTerms = tokenize(query).toSet()
        if (queryTerms.isEmpty()) return emptyList()

        val scored = documents.indices.map { i ->
            val counts = termFreqs[i]
            var score = 0.0
            for (term in queryTerms) {
                val tf = counts[term] ?: 0
                if (tf > 0) score += tf * (idf[term] ?: 1.0)
            }
            RetrievedChunk(documents[i], score)
        }.filter { it.score > 0.0 }

        return scored.sortedWith(
            compareByDescending<RetrievedChunk> { it.score }
                .thenByDescending { it.document.timestamp }
        ).take(topK)
    }

    companion object {
        const val DEFAULT_TOP_K = 6

        /** A term appearing in a document's title counts this many times toward its frequency. */
        const val TITLE_WEIGHT = 2

        // A tiny stop-list — enough to keep "how do I …" style questions from matching on filler,
        // without a full linguistic pipeline.
        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were", "be",
            "for", "on", "at", "by", "with", "as", "how", "what", "when", "where", "why", "who",
            "my", "i", "me", "you", "it", "do", "does", "did", "can", "could", "should", "would",
            "this", "that", "these", "those", "have", "has", "had", "get", "got", "about"
        )

        /** Lowercase, split on non-alphanumerics, drop very short tokens and stop-words. */
        fun tokenize(text: String): List<String> =
            text.lowercase()
                .split(NON_WORD)
                .filter { it.length >= 2 && it !in STOPWORDS }

        private val NON_WORD = Regex("[^a-z0-9]+")
    }
}
