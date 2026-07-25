package com.citation.core.rr

/**
 * Plans a favourite's **full backfill** (slow lane): grab chapters 1..latest, *including behind the
 * current read position*, so a favourited serial is held whole and indefinitely.
 *
 * Two properties matter and both fall out of computing the plan purely from the cached set each
 * call:
 *  - **Resumable** — interrupted mid-backfill, the next call just recomputes what's still missing.
 *  - **Self-healing** — it **reconciles gaps before fetching new**: interior holes (a chapter that
 *    failed to fetch below the high-water mark) are filled first, then the tail of never-fetched
 *    new chapters. So a serial never sits with a permanent hole while the backfill chases the tail.
 */
object BackfillPlanner {

    /**
     * Ordinals to fetch to make `[0, expectedCount)` whole, ordered **gaps-first then new**.
     *
     * A *gap* is a missing ordinal below the highest cached ordinal (an interior hole); a *new*
     * ordinal is missing and above everything cached. Both groups are returned ascending, gaps
     * before new. An empty result means the favourite is already complete.
     */
    fun plan(expectedCount: Int, cachedOrdinals: Set<Int>): List<Int> {
        if (expectedCount <= 0) return emptyList()
        val maxCached = cachedOrdinals.maxOrNull() ?: -1
        val missing = (0 until expectedCount).filter { it !in cachedOrdinals }
        val gaps = missing.filter { it < maxCached }
        val new = missing.filter { it > maxCached }
        return gaps + new
    }

    /** True when nothing is missing in `[0, expectedCount)`. */
    fun isComplete(expectedCount: Int, cachedOrdinals: Set<Int>): Boolean =
        expectedCount > 0 && plan(expectedCount, cachedOrdinals).isEmpty()
}
