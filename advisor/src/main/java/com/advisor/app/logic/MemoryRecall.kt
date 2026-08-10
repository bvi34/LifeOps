package com.advisor.app.logic

/**
 * Ranks long-term [MemoryRecord]s for a question, so only the few most relevant memories enter the
 * prompt. Relevance is lexical overlap (content + tags, reusing [Retriever.tokenize]) plus an
 * explicit tag-focus boost; [MemoryRecord.salience] and [MemoryRecord.pinned] act as gentle priors
 * on top. A pinned memory is always eligible even with no textual overlap — that's the point of
 * pinning — while an unrelated, unpinned memory is left out rather than padding the context.
 *
 * Pure and dependency-free, like the rest of `logic/`, so recall behaviour is unit-testable.
 */
object MemoryRecall {

    const val DEFAULT_LIMIT = 5
    const val TAG_MATCH_WEIGHT = 2.0
    const val PIN_PRIOR = 1.0

    fun recall(
        query: String,
        memories: List<MemoryRecord>,
        focusTags: Set<String> = emptySet(),
        limit: Int = DEFAULT_LIMIT
    ): List<MemoryRecord> {
        if (memories.isEmpty()) return emptyList()

        val queryTokens = Retriever.tokenize(query).toSet()
        val normalizedFocus = focusTags.map { it.lowercase() }.toSet()

        val scored = memories.mapNotNull { memory ->
            val memoryTokens = Retriever.tokenize(memory.searchableText()).toSet()
            val lexical = queryTokens.count { it in memoryTokens }.toDouble()
            val tagFocus = normalizedFocus.count { memory.hasTag(it) } * TAG_MATCH_WEIGHT
            val relevance = lexical + tagFocus

            // Include only memories that actually match — or ones the user pinned to always surface.
            if (relevance <= 0.0 && !memory.pinned) return@mapNotNull null

            val prior = (memory.salience / 100.0) + if (memory.pinned) PIN_PRIOR else 0.0
            memory to (relevance + prior)
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<MemoryRecord, Double>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .take(limit)
            .map { it.first }
    }
}
