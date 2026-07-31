package com.citation.core.reader

/**
 * Measures **engaged** reading time — minutes where you were actually moving through the text, not
 * just leaving the app open on one page. This is what makes reading safe to reward: the receipts are
 * honest at the source, so the economy downstream needs no output cap.
 *
 * The rule: time accrues between reading-progress signals (page turns, scrolls, chapter advances),
 * but each open interval is capped at [maxGapMillis]. So a page you legitimately dwell on for four
 * minutes counts four; a page left open for thirty counts only the cap; backgrounding the reader
 * (or the screen going off) closes the interval and pauses accrual entirely. Idle time can never
 * inflate the total.
 *
 * Stateful but deterministic: the clock is injected and every transition takes an explicit `now`,
 * mirroring [com.citation.core.rr.RateBudget], so the whole meter is unit-testable without a device.
 */
class ReadingMeter(
    val maxGapMillis: Long = DEFAULT_MAX_GAP,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var accruedMillis = 0L
    /** Start of the currently-open interval, or `null` when paused/idle. */
    private var anchorAt: Long? = null

    /** True while accrual is running (the reader is open and visible). */
    val isActive: Boolean @Synchronized get() = anchorAt != null

    /** Begin (or resume) accruing: the reader is on a book and visible. Idempotent while active. */
    @Synchronized
    fun resume(now: Long = clock()) {
        if (anchorAt == null) anchorAt = now
    }

    /** A reading-progress signal (page turn / scroll / chapter advance): bank the open interval, restart it. */
    @Synchronized
    fun progress(now: Long = clock()) {
        closeInterval(now)
        anchorAt = now
    }

    /** Pause accrual: reader backgrounded, screen off, or book closed. Banks the (capped) open interval. */
    @Synchronized
    fun pause(now: Long = clock()) {
        closeInterval(now)
        anchorAt = null
    }

    /** Engaged millis so far, including the current open interval (capped), without mutating state. */
    @Synchronized
    fun engagedMillis(now: Long = clock()): Long = accruedMillis + openInterval(anchorAt, now)

    /**
     * Drain the accrued engaged time: return it and reset the running total to zero. If still active,
     * the current interval is banked and a fresh one starts at [now], so continuous reading keeps
     * counting seamlessly across a flush. Used to persist a session or emit periodic telemetry.
     */
    @Synchronized
    fun flushMillis(now: Long = clock()): Long {
        closeInterval(now)
        val drained = accruedMillis
        accruedMillis = 0
        if (anchorAt != null) anchorAt = now
        return drained
    }

    private fun closeInterval(now: Long) {
        val start = anchorAt ?: return
        accruedMillis += openInterval(start, now)
    }

    /** The capped length of an open interval starting at [start] (null start, or non-positive span, is zero). */
    private fun openInterval(start: Long?, now: Long): Long {
        if (start == null) return 0
        val elapsed = now - start
        return when {
            elapsed <= 0 -> 0
            elapsed > maxGapMillis -> maxGapMillis
            else -> elapsed
        }
    }

    companion object {
        /** Five minutes: the longest a single page may count, so idle dwell can't be farmed. */
        const val DEFAULT_MAX_GAP = 5L * 60 * 1000
    }
}
