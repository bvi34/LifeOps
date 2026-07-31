package com.lifeops.app.util

import com.lifeops.app.data.model.PhoneActivityEvent
import com.lifeops.app.data.model.PhoneActivityType

/**
 * Reconstructs a night's sleep from raw phone-activity events (see PhoneActivityEventEntity) instead
 * of guessing from a single "last app used" timestamp. The device screen is the primary signal: long
 * screen-off stretches overnight are sleep, brief screen-on blips are glances, and longer wake spans
 * are real interruptions.
 *
 * The algorithm, in order:
 *  1. Turn the SCREEN_ON/SCREEN_OFF events into a continuous timeline of screen-off (candidate sleep)
 *     intervals across the [windowStart, now] window.
 *  2. Merge away tiny interruptions — a screen-off stretch broken by a screen-on span shorter than
 *     [SleepInferenceConfig.mergeThresholdMs] (default 60s) is treated as one continuous sleep.
 *  3. Anchor on the longest merged screen-off stretch, then grow the session outward across genuine
 *     interruptions (screen-on spans up to [SleepInferenceConfig.maxInterruptionMs]) as long as real
 *     sleep sits on the far side — this spans a night that was broken by a bathroom trip or two.
 *  4. Derive bedtime, wake, total sleep (span minus awake interruptions), the interruption count, and
 *     the longest uninterrupted stretch.
 *
 * Charging events are collected too (they corroborate bedtime and are kept for future tuning) but the
 * v1 reconstruction leans on the stronger screen signal alone. Returns null when there isn't enough
 * to be confident — no screen events, or nothing that looks like a real night — so callers can fall
 * back to a hand-entered / screen-time estimate. Pure and side-effect-free so it's fully unit-tested.
 */
object SleepInferenceService {

    /** Reconstructed night. Millis are epoch; minute fields are clamped to a sane [0, 16h]. */
    data class SleepReconstruction(
        val bedtimeMillis: Long,
        val wakeMillis: Long,
        val totalSleepMinutes: Int,
        val interruptions: Int,
        val longestSleepMinutes: Int
    )

    data class SleepInferenceConfig(
        /** Screen-on blips shorter than this are noise and are merged into the surrounding sleep. */
        val mergeThresholdMs: Long = 60_000L,
        /** The longest a wake span can be and still count as an in-night interruption (vs. morning). */
        val maxInterruptionMs: Long = 60L * 60_000L,
        /** A reconstructed night shorter than this is treated as "not captured" → null. */
        val minSessionMs: Long = 120L * 60_000L,
        /** A neighbouring sleep block must be at least this long to absorb an interruption across it. */
        val minAdjacentSleepMs: Long = 10L * 60_000L
    )

    private const val MAX_SLEEP_MINUTES = 16 * 60

    private data class Interval(val start: Long, val end: Long) {
        val duration: Long get() = end - start
    }

    fun reconstruct(
        events: List<PhoneActivityEvent>,
        windowStartMillis: Long,
        nowMillis: Long,
        config: SleepInferenceConfig = SleepInferenceConfig()
    ): SleepReconstruction? {
        if (nowMillis <= windowStartMillis) return null

        // 1. Screen-off intervals across the window. Screen is the sleep signal; charging is ignored
        //    for timing in v1 (still collected for future refinement).
        val screenEvents = events
            .filter { it.type == PhoneActivityType.SCREEN_ON || it.type == PhoneActivityType.SCREEN_OFF }
            .filter { it.occurredAt in windowStartMillis..nowMillis }
            .sortedBy { it.occurredAt }
        if (screenEvents.isEmpty()) return null

        val offIntervals = screenOffIntervals(screenEvents, windowStartMillis, nowMillis)
        if (offIntervals.isEmpty()) return null

        // 2. Merge tiny (<mergeThreshold) screen-on blips between off-intervals into continuous sleep.
        val merged = mergeTinyGaps(offIntervals, config.mergeThresholdMs)

        // 3. Anchor on the longest sleep block, then extend across real interruptions.
        val anchor = merged.maxByOrNull { it.duration } ?: return null
        var start = anchor.start
        var end = anchor.end
        var longest = anchor.duration
        val interruptionDurations = mutableListOf<Long>()

        // Extend earlier: absorb a preceding sleep block if the awake gap to it is a plausible
        // interruption and that block is substantial.
        while (true) {
            val prev = merged.filter { it.end <= start }.maxByOrNull { it.end } ?: break
            val awake = start - prev.end
            if (awake in 1..config.maxInterruptionMs && prev.duration >= config.minAdjacentSleepMs) {
                if (awake >= config.mergeThresholdMs) interruptionDurations.add(awake)
                start = prev.start
                if (prev.duration > longest) longest = prev.duration
            } else break
        }

        // Extend later, symmetrically.
        while (true) {
            val next = merged.filter { it.start >= end }.minByOrNull { it.start } ?: break
            val awake = next.start - end
            if (awake in 1..config.maxInterruptionMs && next.duration >= config.minAdjacentSleepMs) {
                if (awake >= config.mergeThresholdMs) interruptionDurations.add(awake)
                end = next.end
                if (next.duration > longest) longest = next.duration
            } else break
        }

        if (end - start < config.minSessionMs) return null

        // 4. Total sleep is the session span minus the awake interruptions inside it.
        val awakeInside = interruptionDurations.sum()
        val sleepMs = (end - start) - awakeInside

        return SleepReconstruction(
            bedtimeMillis = start,
            wakeMillis = end,
            totalSleepMinutes = clampMinutes(sleepMs),
            interruptions = interruptionDurations.size,
            longestSleepMinutes = clampMinutes(longest)
        )
    }

    /**
     * The stretches where the screen was off, across [windowStart, now]. The state before the first
     * event is inferred from that event's type (a leading SCREEN_ON means the screen was off before
     * it). State is taken directly from each event's type, so a stray duplicate can't desync it.
     */
    private fun screenOffIntervals(
        screenEvents: List<PhoneActivityEvent>,
        windowStart: Long,
        now: Long
    ): List<Interval> {
        val out = mutableListOf<Interval>()
        // The screen state we start the window in is the opposite of the first event's type: a
        // leading SCREEN_ON means the screen was off beforehand, while a leading SCREEN_OFF means it
        // was on. `off` == screen currently off.
        var off = screenEvents.first().type == PhoneActivityType.SCREEN_ON
        var offStart = if (off) windowStart else -1L

        for (event in screenEvents) {
            val nowOff = event.type == PhoneActivityType.SCREEN_OFF
            if (nowOff && !off) {
                // Screen just turned off — an off-interval begins here.
                offStart = event.occurredAt
                off = true
            } else if (!nowOff && off) {
                // Screen just turned on — close the current off-interval.
                if (offStart in windowStart until event.occurredAt) {
                    out.add(Interval(offStart, event.occurredAt))
                }
                offStart = -1L
                off = false
            }
        }
        // Trailing off-interval up to now.
        if (off && offStart in windowStart until now) {
            out.add(Interval(offStart, now))
        }
        return out.filter { it.duration > 0 }
    }

    /** Fuse off-intervals separated by an on-span shorter than [mergeThresholdMs] into one. */
    private fun mergeTinyGaps(offIntervals: List<Interval>, mergeThresholdMs: Long): List<Interval> {
        val sorted = offIntervals.sortedBy { it.start }
        val out = mutableListOf<Interval>()
        for (interval in sorted) {
            val last = out.lastOrNull()
            if (last != null && interval.start - last.end < mergeThresholdMs) {
                out[out.lastIndex] = Interval(last.start, interval.end)
            } else {
                out.add(interval)
            }
        }
        return out
    }

    private fun clampMinutes(ms: Long): Int =
        (ms / 60_000L).toInt().coerceIn(0, MAX_SLEEP_MINUTES)
}
