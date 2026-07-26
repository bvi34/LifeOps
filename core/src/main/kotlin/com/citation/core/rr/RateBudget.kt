package com.citation.core.rr

/**
 * A sliding-window rate limiter enforcing **two ceilings at once**: a per-hour rate and a per-day
 * backstop. Royal Road asks that body scrapes stay polite (~5–6/hour), with a daily cap so a burst
 * of favourites can't quietly run all day; the RSS *feeds*, being blessed and tiny, get a much
 * looser budget. One class serves both — the two budgets differ only by their numbers.
 *
 * The clock is injectable so the policy is deterministic under test. Timestamps older than a day are
 * pruned lazily on each call, so memory stays bounded to a day's worth of hits.
 */
class RateBudget(
    val maxPerHour: Int,
    val maxPerDay: Int,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val hits = ArrayDeque<Long>()

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24L * HOUR
    }

    /** True if a fetch is allowed right now without recording one. */
    @Synchronized
    fun canFetch(now: Long = clock()): Boolean {
        prune(now)
        return countSince(now - HOUR) < maxPerHour && hits.size < maxPerDay
    }

    /**
     * Try to consume one unit of budget: if allowed, record the hit and return `true`; otherwise
     * leave the budget untouched and return `false`. This is the call the fetch loop makes before
     * each request.
     */
    @Synchronized
    fun tryAcquire(now: Long = clock()): Boolean {
        if (!canFetch(now)) return false
        hits.addLast(now)
        return true
    }

    /**
     * The earliest time a fetch will be allowed again (equal to [now] when one is allowed now). Lets
     * the worker schedule its next wake-up instead of busy-polling.
     */
    @Synchronized
    fun nextAvailable(now: Long = clock()): Long {
        prune(now)
        if (canFetch(now)) return now
        val hourFree = if (countSince(now - HOUR) >= maxPerHour) nthOldestInHour(now) + HOUR else now
        val dayFree = if (hits.size >= maxPerDay) (hits.firstOrNull() ?: now) + DAY else now
        return maxOf(hourFree, dayFree)
    }

    /** Hits recorded in the last hour — for surfacing budget headroom in the UI. */
    @Synchronized
    fun usedThisHour(now: Long = clock()): Int {
        prune(now)
        return countSince(now - HOUR)
    }

    private fun prune(now: Long) {
        val dayAgo = now - DAY
        while (hits.isNotEmpty() && hits.first() <= dayAgo) hits.removeFirst()
    }

    private fun countSince(threshold: Long): Int = hits.count { it > threshold }

    /** The oldest hit still inside the trailing hour — when it ages out, an hour slot frees up. */
    private fun nthOldestInHour(now: Long): Long =
        hits.filter { it > now - HOUR }.minOrNull() ?: now
}
