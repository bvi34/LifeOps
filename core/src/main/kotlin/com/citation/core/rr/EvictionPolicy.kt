package com.citation.core.rr

import com.citation.core.model.SourceType
import com.citation.core.store.Ownership

/**
 * Decides which borrowed serials the background eviction job may reclaim.
 *
 * The rule is **7 days from last read for non-favourites** — a serial you haven't touched in a week
 * and haven't favourited is a reasonable thing to drop, since it's refetchable. Two protections are
 * *structural*, not just checks: a favourite or a currently-open story can never be selected,
 * because [Ownership.isEvictable] already refuses them before the age test is even consulted. Owned
 * content (EPUB/PDF) isn't borrowed cache at all, so it can never reach this policy.
 */
object EvictionPolicy {

    const val DEFAULT_RETENTION_DAYS = 7
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * A serial the eviction job is considering.
     * @property lastReadAt epoch millis of last read (the eviction clock resets on every read).
     * @property isFavorite favourited serials are kept indefinitely, never evicted.
     * @property isOpen the story is currently open in the reader.
     */
    data class Candidate(
        val fictionId: Long,
        val lastReadAt: Long,
        val isFavorite: Boolean,
        val isOpen: Boolean
    )

    /**
     * Fiction ids safe to evict at [now]: borrowed, not favourite, not open, and untouched for at
     * least [retentionDays]. The [Ownership.isEvictable] guard makes the favourite/open exemptions
     * structural — this function cannot return a protected serial even if the age test passed.
     */
    fun evictable(
        candidates: List<Candidate>,
        now: Long,
        retentionDays: Int = DEFAULT_RETENTION_DAYS
    ): List<Long> {
        val cutoff = retentionDays * DAY_MILLIS
        return candidates.filter { c ->
            Ownership.isEvictable(SourceType.ROYAL_ROAD, isFavorite = c.isFavorite, isOpen = c.isOpen) &&
                (now - c.lastReadAt) >= cutoff
        }.map { it.fictionId }
    }
}
