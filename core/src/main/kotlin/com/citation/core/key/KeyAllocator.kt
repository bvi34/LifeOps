package com.citation.core.key

import java.util.concurrent.atomic.AtomicLong

/**
 * Mints [EntityKey]s for one app namespace, offline, with no coordination.
 *
 * Sequences are monotonic **per entity type** within the namespace, so `Book` and `Note` count
 * independently (`ER-Book-1`, `ER-Book-2`, `ER-Note-1`). The allocator is the single place a new
 * key is born; persistence hands it the highest sequence seen per type at startup ([seedFrom]) so
 * minting resumes without gaps or reuse across process restarts.
 *
 * Thread-safe: [next] uses atomic counters, so concurrent capture (highlight while a backfill job
 * runs, say) can never mint the same key twice.
 */
class KeyAllocator(
    private val namespace: String = EntityKey.CITATION_NAMESPACE,
    seed: Map<String, Long> = emptyMap()
) {
    private val counters = HashMap<String, AtomicLong>()

    init {
        seed.forEach { (type, highest) -> counters[type] = AtomicLong(highest) }
    }

    /** Mint the next key for [type], e.g. `next(EntityType.NOTE)` → `ER-Note-89`. */
    @Synchronized
    fun next(type: String): EntityKey {
        val counter = counters.getOrPut(type) { AtomicLong(0) }
        return EntityKey(namespace, type, counter.incrementAndGet())
    }

    /**
     * Re-seed [type]'s counter to the highest sequence already persisted, so the next mint continues
     * the run. Idempotent and safe to call with a lower value (never rewinds).
     */
    @Synchronized
    fun seedFrom(type: String, highestSequence: Long) {
        val counter = counters.getOrPut(type) { AtomicLong(0) }
        if (highestSequence > counter.get()) counter.set(highestSequence)
    }

    /** The highest sequence minted for [type] so far (0 if none) — for checkpointing to storage. */
    @Synchronized
    fun highWater(type: String): Long = counters[type]?.get() ?: 0L
}
