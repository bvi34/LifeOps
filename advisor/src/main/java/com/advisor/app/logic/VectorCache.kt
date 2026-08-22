package com.advisor.app.logic

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

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
 * A store of computed embeddings so the corpus isn't re-embedded on every question.
 *
 * Two implementations. [inMemory] keeps nothing at rest, so the only cost is re-embedding once per
 * process. [persistent] additionally keeps a copy on disk, which removes that cold-start cost — worth
 * it because re-embedding a corpus is the slowest thing that happens on the first question after an
 * app start, and it happened on *every* app start.
 *
 * What the persistent form writes is worth being precise about, because Advisor's contract is that it
 * keeps no copy of the other apps' data at rest: it stores document *ids* (already persisted anyway,
 * as the citations on stored answers), a hash of each document's text, and the vectors. It does not
 * store titles or bodies. It lives in the cache directory, so the system may delete it at any time and
 * nothing breaks when it does — that is the honest place for something whose loss costs only time.
 */
interface VectorCache {

    fun get(key: VectorKey): FloatArray?

    fun put(key: VectorKey, vector: FloatArray)

    /**
     * Commit anything held only in memory. Called once after a round of embedding rather than per
     * vector, so a cache backed by storage writes once for a corpus instead of once per document.
     * The default does nothing, which is right for a cache that never outlives the process.
     */
    fun flush() {}

    companion object {
        /** Hash a document's identity-bearing text, for [VectorKey.contentHash]. */
        fun contentHash(document: KnowledgeDocument): Int =
            31 * document.title.hashCode() + document.body.hashCode()

        /** A bounded, thread-safe in-memory cache with simple LRU eviction. */
        fun inMemory(maxEntries: Int = 4096): VectorCache = InMemoryVectorCache(maxEntries)

        /**
         * An in-memory cache that survives restarts by keeping a copy in [file]. See
         * [FileVectorCache] for what is and isn't written there.
         */
        fun persistent(file: File, maxEntries: Int = 4096): VectorCache =
            FileVectorCache(file, maxEntries)
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

/**
 * An LRU cache that reloads itself from [file] on first use and writes itself back on [flush].
 *
 * Vectors are expensive to compute and cheap to store — a few hundred documents is under a megabyte —
 * and losing them costs a slow first question, not correctness. So the file is treated as strictly
 * disposable: anything unreadable, truncated, or written by a different embedding model is discarded
 * without complaint and rebuilt.
 *
 * Whole-file rewrite rather than an append log. A corpus is embedded in one burst and then read many
 * times, so there is one write per burst either way, and rewriting keeps the format trivial and the
 * file self-consistent — a half-appended record after a kill would have to be detected and repaired,
 * for no gain. The write goes to a sibling temp file and is renamed over the target, so an interrupted
 * flush leaves the previous file intact rather than a corrupt one.
 */
private class FileVectorCache(
    private val file: File,
    private val maxEntries: Int
) : VectorCache {

    private val map = object : LinkedHashMap<VectorKey, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VectorKey, FloatArray>): Boolean =
            size > maxEntries
    }

    /** The model every vector in [map] came from; vectors from different models aren't comparable. */
    private var embedderId: String? = null
    private var loaded = false
    private var dirty = false

    @Synchronized
    override fun get(key: VectorKey): FloatArray? {
        load()
        return map[key]
    }

    @Synchronized
    override fun put(key: VectorKey, vector: FloatArray) {
        load()
        // A new model invalidates everything: the old vectors describe the same documents in a space
        // this one knows nothing about, so keeping them would only waste the file.
        if (embedderId != null && embedderId != key.embedderId) {
            map.clear()
        }
        embedderId = key.embedderId
        map[key] = vector
        dirty = true
    }

    @Synchronized
    override fun flush() {
        if (!dirty) return
        dirty = false
        val id = embedderId ?: return
        val dims = map.values.firstOrNull()?.size ?: return
        val temp = File(file.parentFile, "${file.name}.tmp")
        val written = runCatching {
            file.parentFile?.mkdirs()
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeUTF(MAGIC)
                out.writeInt(VERSION)
                out.writeUTF(id)
                out.writeInt(dims)
                // Only vectors of the header's width; a model swap mid-session could leave others.
                val entries = map.entries.filter { it.value.size == dims }
                out.writeInt(entries.size)
                for ((key, vector) in entries) {
                    out.writeUTF(key.documentId)
                    out.writeInt(key.contentHash)
                    for (value in vector) out.writeFloat(value)
                }
            }
            temp.renameTo(file) || (file.delete() && temp.renameTo(file))
        }.getOrDefault(false)
        if (!written) temp.delete()
    }

    /** Read the file once per process. Anything wrong with it means an empty cache, never a failure. */
    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != MAGIC || input.readInt() != VERSION) return
                val id = input.readUTF()
                val dims = input.readInt()
                val count = input.readInt()
                if (dims !in 1..MAX_DIMS || count < 0) return
                for (i in 0 until minOf(count, maxEntries)) {
                    val documentId = input.readUTF()
                    val contentHash = input.readInt()
                    val vector = FloatArray(dims) { input.readFloat() }
                    map[VectorKey(documentId, contentHash, id)] = vector
                }
                embedderId = id
            }
        }.onFailure {
            // Truncated or corrupt: start clean rather than serving half a cache.
            map.clear()
            embedderId = null
        }
    }

    private companion object {
        const val MAGIC = "advisor-vectors"
        const val VERSION = 1

        /** A sanity bound on the header, so a corrupt width can't ask for a huge allocation. */
        const val MAX_DIMS = 8192
    }
}
