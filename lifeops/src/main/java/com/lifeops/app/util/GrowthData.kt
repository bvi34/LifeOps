package com.lifeops.app.util

/**
 * Pure assembler that turns the app's live aspects + sealed week snapshots into the
 * ([GrowthRings.AspectRef], [GrowthRings.WeekInput]) pair the renderer consumes.
 *
 * Source-of-truth rule (honours the one invariant):
 *   - A closed week with a sealed snapshot uses that snapshot's hours/colour/name. Sealed
 *     history is immutable — it survives the aspect being deleted, recoloured or renamed.
 *   - The open week (and any pre-backfill closed week with no sealed history) falls back to
 *     live time-entry minutes, so existing data still renders.
 *
 * Kept Android-free so the deletion-survival behaviour is unit-testable on the JVM.
 */
object GrowthData {

    /** An aspect currently present in the app (archived ones included). */
    data class LiveAspect(val id: String, val name: String, val colorHex: String)

    /** Per-aspect record sealed into a closed week's snapshot at week-close. */
    data class AspectHist(val minutes: Int, val name: String, val colorHex: String)

    data class WeekSource(
        val weekId: String,
        val startDate: String,
        val isClosed: Boolean,
        /** Sealed at close; empty for the open week or any pre-backfill week. */
        val aspectHistory: Map<String, AspectHist>,
        /** Live fallback: aspectId -> minutes from current time-entries. */
        val liveMinutesByAspect: Map<String, Int>
    )

    data class Assembled(
        val aspects: List<GrowthRings.AspectRef>,
        val weeks: List<GrowthRings.WeekInput>
    )

    fun assemble(liveAspects: List<LiveAspect>, weeks: List<WeekSource>): Assembled {
        val liveById = liveAspects.associateBy { it.id }
        // Last-known name/colour for aspects no longer present live (deleted). Walking
        // oldest -> newest means the most recent sealed value wins.
        val preserved = HashMap<String, GrowthRings.AspectRef>()

        val sortedWeeks = weeks.sortedBy { it.startDate }
        val weekInputs = ArrayList<GrowthRings.WeekInput>(sortedWeeks.size)
        for (w in sortedWeeks) {
            val minutes: Map<String, Int> = if (w.aspectHistory.isNotEmpty()) {
                for ((id, h) in w.aspectHistory) {
                    if (id !in liveById) preserved[id] = GrowthRings.AspectRef(id, h.name, h.colorHex)
                }
                w.aspectHistory.mapValues { it.value.minutes }
            } else {
                w.liveMinutesByAspect
            }
            val hoursByAspect = minutes.filterValues { it > 0 }.mapValues { it.value / 60.0 }
            weekInputs.add(GrowthRings.WeekInput(w.weekId, w.startDate, hoursByAspect))
        }

        // Global append-only aspect order: every id seen anywhere, sorted by stable id.
        val allIds = LinkedHashSet<String>()
        liveAspects.forEach { allIds.add(it.id) }
        weekInputs.forEach { wi -> wi.hoursByAspect.keys.forEach { allIds.add(it) } }
        val aspects = allIds
            .map { id ->
                liveById[id]?.let { GrowthRings.AspectRef(it.id, it.name, it.colorHex) }
                    ?: preserved[id]
                    ?: GrowthRings.AspectRef(id, "(removed)", GrowthRings.SCAR_COLOR)
            }
            .sortedBy { it.id }

        return Assembled(aspects, weekInputs)
    }
}
