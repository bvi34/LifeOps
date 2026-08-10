package com.advisor.app.logic

/**
 * Identifies one cached embedding. A vector is only reusable when the document, its *content*, and the
 * model that made it all match:
 *
 * - [documentId] — the stable `"<source>:<kind>:<rowId>"` id. It carries the source app, so a cache is
 *   only ever consulted for documents that are in the current (already permission-filtered) corpus —
 *   a revoked app's rows simply aren't looked up, so revocation stays instant with no stale copy used.
 * - [contentHash] — a hash of the title+body. If a row is edited its hash changes and the old vector
 *   misses, forcing a re-embed. There is never a vector that describes text that no longer exists.
 * - [embedderId] — the model identity. Vectors from different models aren't comparable, so switching
 *   the embedding model invalidates the whole cache by construction.
 */
data class VectorKey(
    val documentId: String,
    val contentHash: Int,
    val embedderId: String
)

/**
 * A store of computed embeddings so the corpus isn't re-embedded on every question. The default
 * implementation is **in-memory only** — deliberately. Advisor's contract is that it keeps no copy of
 * the other apps' data at rest (so backup stays simple and a revoked app leaks nothing); an in-memory
 * cache honours that exactly — nothing is persisted, so the only cost is re-embedding once per process
 * after a cold start, while every question within a session is fast. A persistent implementation (e.g.
 * in `cacheDir`) can drop in behind this interface later if cold-start cost ever matters.
 */
interface VectorCache {

    fun get(key: VectorKey): FloatArray?

    fun put(key: VectorKey, vector: FloatArray)

    companion object {
        /** Hash a document's identity-bearing text, for [VectorKey.contentHash]. */
        fun contentHash(document: KnowledgeDocument): Int =
            31 * document.title.hashCode() + document.body.hashCode()

        /** A bounded, thread-safe in-memory cache with simple LRU eviction. */
        fun inMemory(maxEntries: Int = 4096): VectorCache = InMemoryVectorCache(maxEntries)
    }
}

private class InMemoryVectorCache(private val maxEntries: Int) : VectorCache {

    // Access-order LinkedHashMap = LRU: the eldest (least-recently-accessed) entry is evicted first.
    private val map = object : LinkedHashMap<VectorKey, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VectorKey, FloatArray>): Boolean =
            size > maxEntries
    }

    @Synchronized
    override fun get(key: VectorKey): FloatArray? = map[key]

    @Synchronized
    override fun put(key: VectorKey, vector: FloatArray) {
        map[key] = vector
    }
}
