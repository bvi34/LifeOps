package com.lifeops.app.util

/**
 * How reading turns into the resource economy — the pure policy, Android-free and JVM-tested like
 * [ScoringUtils] / [WeekReview].
 *
 * Two decisions live here:
 *  - **Category** (a report/label dimension): a source's default filing — O'Reilly is *Learning*,
 *    Royal Road is *Fun*, owned files (EPUB/PDF) lean *Learning*. Both categories earn identically;
 *    the split exists so Reports can show whether you're reading to grow or to escape.
 *  - **Points**: engaged reading minutes → resource points at a flat rate (default 5/hour). Applied
 *    to the *total* engaged minutes for the week, floored once, so remainders aren't lost per-entry.
 *
 * Only **engaged** minutes reach here — the reader's `ReadingMeter` already excludes idle time — so
 * there is no output cap: the receipts are honest at the source. The points are folded into a
 * user-chosen aspect's week-close earnings, which then flow through the normal aspect→resource
 * mapping. Non-fungibility is preserved: reading is genuine effort minting an aspect's own resource,
 * never a conversion between resources.
 */
enum class ReadingCategory { LEARNING, FUN }

object ReadingRewards {

    /** The default reward rate: five resource points per engaged hour of reading. */
    const val DEFAULT_POINTS_PER_HOUR = 5

    /** A source type's default category. [sourceType] is the `SourceType` name (case-insensitive). */
    fun defaultCategory(sourceType: String): ReadingCategory =
        when (sourceType.trim().uppercase()) {
            "ROYAL_ROAD" -> ReadingCategory.FUN
            else -> ReadingCategory.LEARNING // EPUB, PDF, OREILLY, and anything unknown
        }

    /**
     * Resource points for [engagedMinutes] of reading at [pointsPerHour] (flat, floored). Pass the
     * week's *total* engaged minutes so the single floor doesn't discard per-entry remainders. Zero
     * or negative inputs earn nothing.
     */
    fun points(engagedMinutes: Int, pointsPerHour: Int = DEFAULT_POINTS_PER_HOUR): Int {
        if (engagedMinutes <= 0 || pointsPerHour <= 0) return 0
        return engagedMinutes * pointsPerHour / 60
    }
}
