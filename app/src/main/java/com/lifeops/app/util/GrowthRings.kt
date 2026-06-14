package com.lifeops.app.util

import kotlin.math.max
import kotlin.math.min

/**
 * Growth Record (Rings) — pure geometry, proportion and colour logic. No Android
 * dependencies, so every formula below is unit-testable on the JVM and reusable by
 * both the on-screen Compose renderer and the SVG export path.
 *
 * THE ONE INVARIANT
 * -----------------
 * A ring, once drawn, never changes. Appending week N+1 must not move, resize, recolour,
 * or restack any earlier ring. Every rule here is subordinate to that:
 *   - thickness is flat (the *view* zooms; the geometry never does),
 *   - a ring's radius depends only on the weeks *before* it (all immutable),
 *   - colours and radial positions map to a stable aspect id, never an array index.
 *
 * The aspect list is append-only: a new aspect simply never appears in old rings (it had
 * zero hours then), so adding one leaves every earlier ring byte-identical.
 *
 * Constants and formulas are ported verbatim from the prototype spec.
 */
object GrowthRings {

    const val R0 = 16.0           // inner seed radius (local units)
    const val RING_T = 10.0       // every active ring is exactly this thick — never varies
    const val SCAR_RING_T = 5.0   // a skipped (zero-hour) week
    const val RING_GAP = 1.5      // gap between rings
    const val FLOOR = 0.025       // min share for any aspect with > 0 hours
    const val HOURS_BASELINE = 50.0
    const val GLOW_STDDEV = 4.5   // feGaussianBlur stdDeviation for the glow halo
    const val SCAR_COLOR = "#6B7280"

    enum class BandKind { FILL, GLOW }

    /** A stable, ordered aspect reference. [id] anchors colour + radial track forever. */
    data class AspectRef(val id: String, val name: String, val colorHex: String)

    /** One week's hours, keyed by aspect id (position-independent — enforces the invariant). */
    data class WeekInput(
        val weekId: String,
        val label: String,
        val hoursByAspect: Map<String, Double>
    )

    /** A shape-agnostic render primitive: a coloured annulus, either a crisp fill or a glow. */
    data class Band(
        val aspectId: String?,   // null == scar
        val innerR: Double,
        val outerR: Double,
        val colorHex: String,
        val alpha: Double,
        val kind: BandKind
    )

    data class Ring(
        val weekId: String,
        val index: Int,
        val label: String,
        val innerR: Double,
        val outerR: Double,
        val isScar: Boolean,
        /** GLOW primitives first, then FILL — so a single in-order draw layers correctly. */
        val bands: List<Band>
    )

    data class Scene(
        val rings: List<Ring>,
        val seedR: Double,
        val contentRadius: Double
    ) {
        /** Every glow primitive across all rings — draw these first, behind all crisp bands. */
        fun glowBands(): List<Band> = rings.flatMap { r -> r.bands.filter { it.kind == BandKind.GLOW } }

        /** Every crisp fill primitive across all rings — draw these on top of the glow layer. */
        fun fillBands(): List<Band> = rings.flatMap { r -> r.bands.filter { it.kind == BandKind.FILL } }
    }

    /** Glow halo strength for a band: g = clamp((hours - 50) / 60, 0, 0.9). */
    fun glowStrength(hours: Double): Double =
        ((hours - HOURS_BASELINE) / 60.0).coerceIn(0.0, 0.9)

    fun ringThickness(isScar: Boolean): Double = if (isScar) SCAR_RING_T else RING_T

    /**
     * Builds the full render scene. [aspects] is the fixed, append-only, id-sorted order;
     * [weeks] is chronological (oldest first). The radius of ring k accumulates outward from
     * [R0] using only the thicknesses of rings 0..k-1, so ring k lands at the same radius
     * whether the record holds k or k+50 weeks.
     */
    fun computeScene(
        aspects: List<AspectRef>,
        weeks: List<WeekInput>,
        colorByHours: Boolean = true,
        glowEnabled: Boolean = true
    ): Scene {
        val rings = ArrayList<Ring>(weeks.size)
        var innerR = R0
        weeks.forEachIndexed { index, week ->
            val hours = aspects.map { max(0.0, week.hoursByAspect[it.id] ?: 0.0) }
            val totalHours = hours.sum()
            val isScar = totalHours <= 0.0
            val ringInner = innerR
            val ringOuter = ringInner + ringThickness(isScar)
            val bands = if (isScar) {
                // A scar is never a blank gap: one permanent grey ring.
                listOf(Band(null, ringInner, ringOuter, SCAR_COLOR, 1.0, BandKind.FILL))
            } else {
                activeBands(aspects, hours, totalHours, ringInner, colorByHours, glowEnabled)
            }
            rings.add(Ring(week.weekId, index, week.label, ringInner, ringOuter, isScar, bands))
            innerR = ringOuter + RING_GAP
        }
        return Scene(
            rings = rings,
            seedR = R0 * 0.55,
            contentRadius = rings.lastOrNull()?.outerR ?: R0
        )
    }

    /**
     * Band proportions within one active ring. In the fixed aspect order (innermost = aspect 0):
     *   active  = count of aspects with hours > 0
     *   reserve = min(FLOOR * active, 0.85)
     *   share_j = reserve/active + (1 - reserve) * (hours_j / totalHours)   for hours_j > 0
     * Shares sum to exactly 1 over the active aspects, so the bands fill RING_T precisely.
     * Zero-hour aspects produce no band, but their *track position* is preserved because the
     * order is global: each aspect always sits at the same place relative to the others.
     */
    private fun activeBands(
        aspects: List<AspectRef>,
        hours: List<Double>,
        totalHours: Double,
        ringInner: Double,
        colorByHours: Boolean,
        glowEnabled: Boolean
    ): List<Band> {
        val active = hours.count { it > 0.0 }
        val reserve = min(FLOOR * active, 0.85)
        val glows = ArrayList<Band>()
        val fills = ArrayList<Band>()
        var cursor = ringInner
        for (j in aspects.indices) {
            val h = hours[j]
            if (h <= 0.0) continue
            val share = reserve / active + (1.0 - reserve) * (h / totalHours)
            val bandInner = cursor
            val bandOuter = cursor + share * RING_T
            cursor = bandOuter
            val fillHex = if (colorByHours) GrowthColor.intensify(aspects[j].colorHex, h) else aspects[j].colorHex
            if (colorByHours && glowEnabled) {
                val g = glowStrength(h)
                if (g > 0.0) glows.add(Band(aspects[j].id, bandInner, bandOuter, fillHex, g, BandKind.GLOW))
            }
            fills.add(Band(aspects[j].id, bandInner, bandOuter, fillHex, 1.0, BandKind.FILL))
        }
        glows.addAll(fills)
        return glows
    }
}
