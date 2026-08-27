package com.health.app.logic

import java.time.LocalDate

/**
 * The vaccination record — the single most-demanded document a household is asked to produce.
 *
 * School enrolment, daycare, camp, a new paediatrician, a visa, a job. Every one of them wants the
 * same list, and every household keeps it as a folded card in a drawer that is never where it should
 * be. This is that card, typed up.
 *
 * ### Health ships no schedule, and will not
 *
 * There is deliberately no "due", no "overdue", and no "up to date" anywhere in this file. That is
 * the whole design, and it is the same line `DrugFacts` draws about dosing.
 *
 * An immunisation schedule is not one list. It varies by country, by birth year, by risk group, by
 * whether a dose was given early, by which combination product was used, and by catch-up rules that
 * a clinician applies with judgement. An app holding one hard-coded list would be confidently wrong
 * for a family that moved countries, for a premature baby, for anybody on an accelerated schedule —
 * and "she's up to date" is precisely the sentence somebody would act on without checking.
 *
 * So Health reports **what is recorded** and nothing more. "Three doses recorded, the latest in March
 * 2019" is a fact about this household's records. "She has had all her jabs" is a claim about the
 * world, and this app cannot see the world.
 *
 * ### Provenance is part of the record
 *
 * A dose somebody watched being given and a dose copied off a card years later are both worth
 * having, and are **not** equally reliable — the same principle that makes `createdAt` sit beside
 * every event time elsewhere in Health. [VaccineSource] carries which is which, and the record says
 * so rather than presenting a transcription as an observation.
 */

/** Where a recorded dose came from. Ordered from what Health can most rely on to least. */
enum class VaccineSource(val key: String, val label: String) {
    /** Somebody in the household was there when it was given. */
    WITNESSED("witnessed", "We were there"),

    /** Typed off a card, a portal printout or an after-visit summary. */
    TRANSCRIBED("transcribed", "Copied from a record"),

    /** Nobody has the paperwork; this is what somebody remembers. Worth keeping, worth flagging. */
    RECALLED("recalled", "From memory"),

    /** Nobody said. Health does not guess which of the three it was. */
    UNKNOWN("unknown", "Source not recorded");

    companion object {
        fun fromKey(key: String?): VaccineSource = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/**
 * One dose as this file reasons about it. The database row carries more — the lot number, the site
 * it went into, who gave it — but none of that changes how a record reads back, so none of it is
 * here.
 */
data class VaccineDose(
    val id: String,
    /** As written down: "MMR", "DTaP", "Influenza". Grouped on this, compared letters-and-digits only. */
    val vaccine: String,
    /** ISO at whatever precision was recorded — a transcribed card is often only a month. */
    val givenDate: String?,
    /** Which dose in its series, when the record said. Null is ordinary and is not filled in. */
    val doseNumber: Int?,
    val source: VaccineSource
) {
    val date: PartialDate? get() = PartialDates.parse(givenDate)

    /** "Dose 2 · 14 March 2019 · copied from a record" — everything known, nothing implied. */
    val descriptor: String
        get() = listOfNotNull(
            doseNumber?.let { "Dose $it" },
            PartialDates.format(date) ?: "Date not recorded",
            source.takeIf { it != VaccineSource.UNKNOWN }?.label
        ).joinToString(" · ")
}

/**
 * Every recorded dose of one vaccine, oldest first — which is how a series is read, and how it is
 * copied onto a form.
 */
data class VaccineSeries(
    /**
     * The normalised name the doses were grouped on — stable, unique across a person's series, and
     * the right thing for a list to key on. [name] is for reading and could in principle repeat.
     */
    val key: String,
    /** The name as the household wrote it, taken from the most recent dose that named it. */
    val name: String,
    val doses: List<VaccineDose>
) {
    val latest: VaccineDose? get() = doses.lastOrNull { it.date != null } ?: doses.lastOrNull()

    /**
     * "3 doses recorded · latest March 2019".
     *
     * "Recorded" is doing real work in that sentence and is not decoration: it is the difference
     * between reporting this household's paperwork and making a claim about a child's immunity.
     */
    val summary: String
        get() {
            val count = doses.size
            val noun = if (count == 1) "dose" else "doses"
            val latestDate = PartialDates.format(latest?.date)
            return if (latestDate == null) "$count $noun recorded"
            else "$count $noun recorded · latest $latestDate"
        }

    /** Whether any dose in the series is somebody's recollection rather than a record. */
    val hasRecalledDose: Boolean get() = doses.any { it.source == VaccineSource.RECALLED }
}

object Immunizations {

    /**
     * Shown wherever a vaccination record is, for the same reason `Fever.DISCLAIMER` is shown
     * wherever a temperature is banded — the limit has to travel with the feature.
     */
    const val DISCLAIMER: String =
        "This is what your household has written down, not a medical record and not a statement " +
            "that anybody is up to date. Health does not know which vaccines are due at which age — " +
            "that depends on the country, the year and the person. Your doctor's own record is the " +
            "one that counts."

    /**
     * Group doses into series, most recently given first.
     *
     * Grouped on the vaccine name reduced to **letters and digits only**, so "MMR", "M.M.R." and a
     * stray capital are one series rather than three halves of a child's record. The name shown is the
     * one from the most recent dose — if a household has started writing it a new way, that is the way
     * they are writing it now.
     *
     * Series with no dated dose at all sort last: they are still real records, and they are the ones
     * a person is least able to act on, so they do not belong at the top of the list.
     */
    fun group(doses: List<VaccineDose>): List<VaccineSeries> =
        doses
            .filter { it.vaccine.isNotBlank() }
            .groupBy { normalize(it.vaccine) }
            .map { (key, group) ->
                val ordered = group.sortedWith(
                    // Undated doses go last within a series — a series reads 1, 2, 3, and a dose
                    // nobody dated cannot be slotted into that order without inventing its place.
                    compareBy({ it.date?.date ?: LocalDate.MAX }, { it.doseNumber ?: Int.MAX_VALUE })
                )
                VaccineSeries(
                    key = key,
                    name = ordered.lastOrNull { it.vaccine.isNotBlank() }?.vaccine.orEmpty(),
                    doses = ordered
                )
            }
            .sortedWith(
                compareByDescending<VaccineSeries> { it.latest?.date?.date ?: LocalDate.MIN }
                    .thenBy { it.name.lowercase() }
            )

    /**
     * Letters and digits only, lowercased — every separator dropped rather than collapsed.
     *
     * More aggressive than the word-set matching `Allergies` uses, and deliberately so, because the
     * two are guarding against opposite mistakes. There, a false match is a warning that fires on the
     * wrong drug and teaches people to ignore the dialog. Here, a false *split* is a child's
     * vaccination record shown as two half-records on a school form — and the inputs that cause it
     * are exactly what households type: "M.M.R.", "MMR", "Hepatitis-B", "Hepatitis B".
     */
    internal fun normalize(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    /** How many doses are recorded across everything — the number a form's first line asks for. */
    fun totalRecorded(series: List<VaccineSeries>): Int = series.sumOf { it.doses.size }
}
