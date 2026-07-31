package com.lifeops.app.util

import kotlin.math.max

/**
 * Growth Record (Bars) — the same sealed week × aspect × hours data the rings render,
 * reshaped into a stacked-bar timeline. Pure, Android-free, JVM-testable.
 *
 * This is a *view* over the record, not a second source of truth: it consumes the exact
 * same [GrowthRings.AspectRef] / [GrowthRings.WeekInput] pair as [GrowthRings.computeScene],
 * so a week reads identically whether you look at it as a ring or a bar. One bar per week,
 * oldest on the left; within a bar the aspects stack bottom-to-top in the stable append-only
 * order (same order the rings lay bands innermost-first), so a colour keeps its lane across
 * every bar. A zero-hour week is a scar here too — a short grey stub, never a gap.
 */
object GrowthBars {

    const val SCAR_COLOR = GrowthRings.SCAR_COLOR

    /** One aspect's slice of a week's bar, already coloured for the current view options. */
    data class Segment(
        val aspectId: String?,   // null == scar
        val name: String,
        val colorHex: String,
        val hours: Double
    )

    /** One week: a full stacked bar (bottom-to-top segments) or a grey scar stub. */
    data class Bar(
        val weekId: String,
        val label: String,
        val totalHours: Double,
        val isScar: Boolean,
        val segments: List<Segment>
    )

    data class Chart(
        val bars: List<Bar>,
        /** Tallest bar's total hours, for y-axis scaling; never below 1 so scars still show. */
        val maxHours: Double,
        /** Stable aspect order, for a legend that matches the segment lanes. */
        val aspects: List<GrowthRings.AspectRef>
    )

    /**
     * [aspects] is the fixed, id-sorted, append-only order; [weeks] is chronological (oldest
     * first). Colouring mirrors the rings: with [colorByHours] on, each segment is intensified
     * by its own hours via [GrowthColor.intensify]; off, it uses the flat aspect colour.
     */
    fun computeChart(
        aspects: List<GrowthRings.AspectRef>,
        weeks: List<GrowthRings.WeekInput>,
        colorByHours: Boolean = true
    ): Chart {
        val bars = weeks.map { week ->
            val total = aspects.sumOf { max(0.0, week.hoursByAspect[it.id] ?: 0.0) }
            if (total <= 0.0) {
                Bar(
                    weekId = week.weekId,
                    label = week.label,
                    totalHours = 0.0,
                    isScar = true,
                    segments = listOf(Segment(null, "No hours logged", SCAR_COLOR, 0.0))
                )
            } else {
                val segments = aspects.mapNotNull { a ->
                    val h = max(0.0, week.hoursByAspect[a.id] ?: 0.0)
                    if (h <= 0.0) return@mapNotNull null
                    val hex = if (colorByHours) GrowthColor.intensify(a.colorHex, h) else a.colorHex
                    Segment(a.id, a.name, hex, h)
                }
                Bar(week.weekId, week.label, total, false, segments)
            }
        }
        val maxHours = max(1.0, bars.maxOfOrNull { it.totalHours } ?: 0.0)
        return Chart(bars, maxHours, aspects)
    }
}
