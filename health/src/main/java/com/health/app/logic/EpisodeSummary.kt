package com.health.app.logic

import kotlin.math.abs

/** One temperature reading inside an episode, as the summariser needs it. */
data class TempPoint(val atMillis: Long, val celsius: Double, val site: TempSite = TempSite.ORAL) {
    val oralEquivalentC: Double get() = celsius + site.toOralOffsetC
}

/** One symptom, open ([endedAt] null) or resolved. */
data class SymptomPoint(
    val name: String,
    val severity: Int,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null
) {
    val isActive: Boolean get() = endedAtMillis == null
}

/** A dose given during an episode — the medicine's name matters here, unlike in [DoseSchedule]. */
data class DosePoint(val name: String, val atMillis: Long, val amount: Double = 0.0, val unit: String = "")

/** Everything recorded for one illness, flattened for the summariser. */
data class EpisodeFacts(
    val title: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val ageMonths: Int? = null,
    val temps: List<TempPoint> = emptyList(),
    val symptoms: List<SymptomPoint> = emptyList(),
    val doses: List<DosePoint> = emptyList()
)

/** Which way the last few readings are going. */
enum class TempTrend(val label: String) {
    RISING("Rising"),
    FALLING("Falling"),
    STEADY("Steady"),
    UNKNOWN("Not enough readings")
}

/**
 * An illness, read back to you.
 *
 * [advice] holds the standing-back observations that only exist across readings — a fever running
 * into its fourth day, a temperature still climbing — which no single reading can tell you and which
 * are exactly what you forget at 3am on night three.
 */
data class EpisodeSummary(
    val title: String,
    val durationHours: Long,
    val ongoing: Boolean,
    val readingCount: Int,
    val peak: TempPoint?,
    val latest: TempPoint?,
    val latestAssessment: FeverAssessment?,
    val feverReadings: Int,
    val feverRunHours: Long,
    val trend: TempTrend,
    val activeSymptoms: List<SymptomPoint>,
    val resolvedSymptoms: List<SymptomPoint>,
    val doseCount: Int,
    val lastDose: DosePoint?,
    val careLevel: CareLevel,
    val headline: String,
    val advice: List<String>
)

/**
 * Turns the rows of an episode into the few sentences a person actually wants, framework-free so it
 * is unit-tested rather than eyeballed on a screen. Same caveat as [Fever]: this reports what was
 * recorded, it does not diagnose.
 */
object EpisodeSummaries {

    private const val HOUR_MS = 60L * 60 * 1000

    /** A fever this long is the classic "stop waiting it out" mark. */
    private const val LONG_FEVER_HOURS = 72L

    fun summarize(facts: EpisodeFacts, nowMillis: Long): EpisodeSummary {
        val temps = facts.temps.sortedBy { it.atMillis }
        val end = facts.endedAtMillis ?: nowMillis
        val durationHours = ((end - facts.startedAtMillis).coerceAtLeast(0L)) / HOUR_MS

        val peak = temps.maxByOrNull { it.oralEquivalentC }
        val latest = temps.lastOrNull()
        val latestAssessment = latest?.let { Fever.assess(it.celsius, it.site, facts.ageMonths) }
        val feverReadings = temps.count { Fever.bandFor(it.oralEquivalentC) >= FeverBand.FEVER }
        val feverRunHours = feverRunHours(temps, nowMillis)
        val trend = trendOf(temps)

        val activeSymptoms = facts.symptoms.filter { it.isActive }.sortedByDescending { it.severity }
        val resolvedSymptoms = facts.symptoms.filterNot { it.isActive }.sortedByDescending { it.startedAtMillis }
        val lastDose = facts.doses.maxByOrNull { it.atMillis }

        val advice = mutableListOf<String>()
        var care = latestAssessment?.careLevel ?: CareLevel.ROUTINE

        if (feverRunHours >= LONG_FEVER_HOURS) {
            care = maxOf(care, CareLevel.CALL_DOCTOR)
            advice += "Fever has been present for ${feverRunHours / 24} day(s) — a fever that won't " +
                "settle is worth a call."
        }
        if (trend == TempTrend.RISING && (latestAssessment?.isFever == true)) {
            advice += "Still climbing across the last readings."
        }
        if (trend == TempTrend.FALLING && feverReadings > 0) {
            advice += "Coming down over the last readings."
        }
        if (temps.isNotEmpty() && nowMillis - temps.last().atMillis > 12 * HOUR_MS && facts.endedAtMillis == null) {
            advice += "No reading in over 12 hours — take another to keep the picture current."
        }
        latestAssessment?.reasons?.forEach { advice += it }

        return EpisodeSummary(
            title = facts.title,
            durationHours = durationHours,
            ongoing = facts.endedAtMillis == null,
            readingCount = temps.size,
            peak = peak,
            latest = latest,
            latestAssessment = latestAssessment,
            feverReadings = feverReadings,
            feverRunHours = feverRunHours,
            trend = trend,
            activeSymptoms = activeSymptoms,
            resolvedSymptoms = resolvedSymptoms,
            doseCount = facts.doses.size,
            lastDose = lastDose,
            careLevel = care,
            headline = headline(facts, latestAssessment, durationHours, activeSymptoms),
            advice = advice.distinct()
        )
    }

    /**
     * How long the *current* fever run has lasted: from the first feverish reading of the unbroken
     * run ending at the latest reading, to now. A run is broken by a reading below the fever line, so
     * a fever that settles and returns is reported as the new run it is, not as one long siege.
     */
    fun feverRunHours(temps: List<TempPoint>, nowMillis: Long): Long {
        val sorted = temps.sortedBy { it.atMillis }
        if (sorted.isEmpty()) return 0
        if (Fever.bandFor(sorted.last().oralEquivalentC) < FeverBand.FEVER) return 0
        var startAt = sorted.last().atMillis
        for (point in sorted.asReversed()) {
            if (Fever.bandFor(point.oralEquivalentC) < FeverBand.FEVER) break
            startAt = point.atMillis
        }
        return ((nowMillis - startAt).coerceAtLeast(0L)) / HOUR_MS
    }

    /**
     * Direction of travel: the latest reading against the mean of up to three before it. One reading
     * is no trend, and a 0.2 °C wobble is thermometer noise rather than news, so both are reported
     * honestly instead of being dressed up as movement.
     */
    fun trendOf(temps: List<TempPoint>): TempTrend {
        val sorted = temps.sortedBy { it.atMillis }
        if (sorted.size < 2) return TempTrend.UNKNOWN
        val latest = sorted.last().oralEquivalentC
        val previous = sorted.dropLast(1).takeLast(3)
        val baseline = previous.sumOf { it.oralEquivalentC } / previous.size
        val delta = latest - baseline
        return when {
            abs(delta) < 0.2 -> TempTrend.STEADY
            delta > 0 -> TempTrend.RISING
            else -> TempTrend.FALLING
        }
    }

    private fun headline(
        facts: EpisodeFacts,
        latest: FeverAssessment?,
        durationHours: Long,
        activeSymptoms: List<SymptomPoint>
    ): String {
        val days = durationHours / 24
        val age = when {
            facts.endedAtMillis != null -> "ended after ${if (days >= 1) "$days day(s)" else "$durationHours hour(s)"}"
            days >= 1 -> "day ${days + 1}"
            else -> "started ${durationHours}h ago"
        }
        val state = latest?.let { "${it.band.label.lowercase()} at ${Temperature.round1(it.measuredC)} °C" }
            ?: "no temperature recorded"
        val symptoms = if (activeSymptoms.isEmpty()) "" else
            ", ${activeSymptoms.take(3).joinToString { it.name.lowercase() }}"
        return "${facts.title} — $age, $state$symptoms."
    }
}
