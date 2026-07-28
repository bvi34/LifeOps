package com.citation.core.oreilly

import com.citation.core.manifest.Recoverability
import com.citation.core.store.Store

/**
 * The policy for O'Reilly's **warm page cache** — the WebView's own HTTP cache of pages you've already
 * opened, kept so a page reloads fast and can be re-read for a moment when you're briefly offline.
 *
 * This is the one place O'Reilly touches disposable storage, and the rules keep it honest: the warm
 * cache is **never a permanent copy of licensed content**. It is
 *
 *  - **always [Store.DISPOSABLE]** — structurally evictable, never sovereign like your notes/position;
 *  - **[Recoverability.RECLAIMABLE]** — refetchable by simply reopening the book online;
 *  - **TTL-bounded** — dropped once it's gone untouched for [DEFAULT_RETENTION_DAYS], so it can't
 *    quietly accrete into an offline library.
 *
 * The clock is *last opened* (reopening online re-warms it). Everything here is pure so the decisions
 * are unit-tested without a device; the Android layer maps [store]/recoverability onto the real
 * WebView cache and calls [shouldPurge] to decide when to clear it.
 */
object OreillyCachePolicy {

    const val DEFAULT_RETENTION_DAYS = 7
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** The warm cache is borrowed/licensed content — always disposable, never sovereign. Structural. */
    fun store(): Store = Store.DISPOSABLE

    /** Refetchable by reopening online, so the warm cache is always safe to reclaim. */
    fun recoverability(): Recoverability = Recoverability.RECLAIMABLE

    /**
     * True once the warm cache has gone untouched (unopened) for at least [retentionDays] at [now].
     * [lastWarmedAt] is epoch millis of the last online open.
     */
    fun isStale(
        lastWarmedAt: Long,
        now: Long,
        retentionDays: Int = DEFAULT_RETENTION_DAYS
    ): Boolean = (now - lastWarmedAt) >= retentionDays * DAY_MILLIS

    /**
     * Whether to purge the warm cache now: only when there *is* one ([lastWarmedAt] non-null), the
     * reader isn't currently open (never yank the cache out from under an active read), and it's gone
     * [isStale]. A never-warmed cache ([lastWarmedAt] null) purges to `false` — nothing to do.
     */
    fun shouldPurge(
        lastWarmedAt: Long?,
        isReaderOpen: Boolean,
        now: Long,
        retentionDays: Int = DEFAULT_RETENTION_DAYS
    ): Boolean {
        if (lastWarmedAt == null) return false
        if (isReaderOpen) return false
        return isStale(lastWarmedAt, now, retentionDays)
    }
}
