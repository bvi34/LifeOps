package com.health.app.data.model

import com.health.app.logic.Allergies
import com.health.app.logic.AllergyFacts
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.ConditionStatus
import com.health.app.logic.Conditions
import com.health.app.logic.VaccineDose
import com.health.app.logic.VaccineSource

/**
 * The facts that stay true between illnesses: allergies, conditions, vaccinations.
 */

// --- the standing record ----------------------------------------------------------------------------
//
// What is true about a person between illnesses, as against what happened during one. These are the
// facts a babysitter, a school form and a doctor all ask for first, and they were previously a
// sentence inside a profile's free-text note.

/** One recorded allergy, with the enums resolved. */
data class Allergy(
    val id: String,
    val profileId: String,
    val substance: String,
    val kind: AllergyKind,
    val severity: AllergySeverity,
    val reaction: String?,
    val rxcui: String?,
    val noticedDate: String?,
    val note: String?
) {
    /** The shape `logic/Allergies` matches against — the mapping happens here, once. */
    val facts: AllergyFacts get() = AllergyFacts(id, substance, kind, severity, rxcui)

    /** "Medicine · hives · since 2019" — what it is, what it did, and how long they've known. */
    val descriptor: String?
        get() = listOfNotNull(
            kind.label,
            reaction?.trim()?.ifBlank { null },
            Conditions.describeOnset(noticedDate)?.replaceFirstChar { it.lowercase() }
        ).joinToString(" · ").ifBlank { null }
}

/** One long-running condition, with the enums resolved. */
data class Condition(
    val id: String,
    val profileId: String,
    val name: String,
    val status: ConditionStatus,
    val onsetDate: String?,
    val resolvedDate: String?,
    val providerId: String?,
    val monitorReadingType: ReadingType?,
    val note: String?
) {
    /** "Since March 2019 · 7 years", at the precision the onset was actually recorded to. */
    val sinceLabel: String? get() = Conditions.describeOnset(onsetDate)
}

/**
 * One person's standing facts, read as a unit.
 *
 * Deliberately **not** folded into [ProfileSnapshot]. A snapshot is the answer to "how is she right
 * now" and every part of it feeds a care level; an allergy is true whether or not anything is
 * happening, and letting it raise the care level would leave every screen permanently shouting about
 * a fact that changes nothing about today. It is surfaced where it is *actionable* instead — beside
 * a medicine somebody is about to give — and listed where it is *readable*, on the record.
 */
data class StandingRecord(
    val allergies: List<Allergy>,
    val conditions: List<Condition>
) {
    val currentConditions: List<Condition> get() = conditions.filter { it.status.isCurrent }

    /** For the badge that says there is something here worth opening. Null when nothing is recorded. */
    val worstAllergySeverity: AllergySeverity?
        get() = Allergies.worstSeverity(allergies.map { it.facts })

    val isEmpty: Boolean get() = allergies.isEmpty() && conditions.isEmpty()

    companion object {
        val EMPTY = StandingRecord(emptyList(), emptyList())
    }
}

/**
 * One recorded dose, with the enums resolved.
 *
 * [dose] is the shape `logic/Immunizations` groups and reads back; the extra fields here — the lot
 * number, the site, who gave it — are what the detail card shows and play no part in how a record
 * reads.
 */
data class Immunization(
    val id: String,
    val profileId: String,
    val vaccine: String,
    val cvxCode: String?,
    val givenDate: String?,
    val doseNumber: Int?,
    val source: VaccineSource,
    val providerId: String?,
    val lotNumber: String?,
    val site: String?,
    val note: String?
) {
    val dose: VaccineDose
        get() = VaccineDose(
            id = id,
            vaccine = vaccine,
            givenDate = givenDate,
            doseNumber = doseNumber,
            source = source
        )
}
