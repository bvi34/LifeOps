package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The facts that stay true between illnesses: allergies, conditions, vaccinations.
 */

// --- the standing record --------------------------------------------------------------------------
//
// Two tables for the facts that are true about a person between illnesses rather than during one.
// Everything above this line records something that *happened*; these record something that *is*.
//
// Both were previously a sentence inside [ProfileEntity.notes], which is where the app kept its most
// safety-critical data in the one shape nothing could read back — un-listable, un-sortable, and
// above all un-checkable against the bottle somebody is holding.

/**
 * One thing this person must not be given, or must not eat, or reacts to.
 *
 * Per-person and never household-scoped, unlike the cabinet and the care team: an allergy is the
 * least shareable fact in the app. It is also the one field here whose absence must never be read as
 * an all-clear — see `logic/Allergies`, which says so on every check it performs.
 *
 * [rxcui] is what makes a drug allergy exact. Recorded by looking the medicine up rather than typing
 * it, it lets the check match on the concept id instead of on a string, which settles the cases where
 * a household wrote "the pink antibiotic" and the label says something else entirely.
 *
 * [noticedDate] is ISO text at whatever precision anybody knows — `2019`, `2019-03`, `2019-03-14` —
 * and is read by `Conditions.parseOnset`, which shares the rule. Nobody remembers the day.
 */
@Entity(
    tableName = "allergies",
    indices = [Index("profileId"), Index("substance"), Index("rxcui")]
)
data class AllergyEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    /** As written down: "penicillin", "peanuts". Matched as whole words, never as a substring. */
    val substance: String,
    /** [com.health.app.logic.AllergyKind]'s key. Only `drug` takes part in the medicine check. */
    val kind: String,
    /** [com.health.app.logic.AllergySeverity]'s key. `unknown` where nobody wrote it down. */
    val severity: String,
    /** What actually happened — "hives", "throat swelling". The part a doctor asks about first. */
    val reaction: String?,
    /** The RxNorm concept, when the allergy was recorded by lookup rather than typed. */
    val rxcui: String?,
    /** When it was first noticed, at the precision it is known. */
    val noticedDate: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A long-running condition — asthma, eczema, coeliac, a murmur somebody is watching.
 *
 * **Deliberately not an episode.** `logic/Conditions` carries the full argument, but the short of it
 * is that [EpisodeEntity] is built around a bout with an end: it counts days from Day 1, escalates a
 * fever into its fourth day, and is held to one open row per person. An episode left open for nine
 * years would report a four-thousandth day, block every future illness that person has, and corrupt
 * the one number the illness screen exists to give. So this row has an **onset** rather than a start
 * and a [status] rather than an end, and it outlives every episode filed alongside it.
 *
 * [monitorReadingType] is the chronic-care hook: the measurement that actually matters for this
 * condition — oxygen for asthma, blood pressure for hypertension, weight for a thyroid problem.
 * Nullable, and null for most conditions, because Health has no table of which vital belongs to which
 * diagnosis and will not invent one. When somebody sets it, the record screen can say when that
 * number was last taken; when nobody does, it says nothing.
 *
 * [providerId] points at the clinician who manages it, and is a plain nullable id like every other
 * cross-link in this file: striking a doctor off the care team must not delete the fact that
 * somebody has asthma.
 */
@Entity(
    tableName = "conditions",
    indices = [Index("profileId"), Index("status"), Index("providerId")]
)
data class ConditionEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val name: String,
    /** [com.health.app.logic.ConditionStatus]'s key — active, remission, resolved. */
    val status: String,
    /** ISO at whatever precision was given. See `Conditions.parseOnset`. */
    val onsetDate: String?,
    /** When it stopped, for the resolved ones. Same precision rule. */
    val resolvedDate: String?,
    val providerId: String?,
    /** [com.health.app.data.model.ReadingType]'s key, when one measurement is the one that matters. */
    val monitorReadingType: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One recorded dose of one vaccine.
 *
 * The most-demanded document a household is asked to produce — school, daycare, camp, a new
 * paediatrician, a visa — and the one it invariably keeps as a folded card in a drawer.
 *
 * **Health ships no immunisation schedule**, so there is no column here for "due", and there will
 * not be one. `logic/Immunizations` carries the whole argument; the short of it is that a schedule
 * varies by country, birth year, risk group and catch-up rules applied with judgement, and "she's up
 * to date" is exactly the sentence somebody would act on without checking. This table records what
 * was given. It does not have an opinion about what wasn't.
 *
 * [source] is the provenance, and it is part of the record rather than metadata about it — the same
 * principle that puts `createdAt` beside every event time in this file. A dose somebody watched being
 * given and a dose copied off a card years later are both worth having and are not equally reliable.
 *
 * [givenDate] is ISO at whatever precision was recorded. A transcribed card is very often only a
 * month, and rounding that to a day would invent one — see `logic/PartialDate`.
 */
@Entity(
    tableName = "immunizations",
    indices = [Index("profileId"), Index("vaccine"), Index("givenDate")]
)
data class ImmunizationEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    /** As written down: "MMR", "DTaP", "Influenza". Grouped on this, compared letters-and-digits only. */
    val vaccine: String,
    /**
     * The CDC's CVX code, when somebody has the paperwork that carries one.
     *
     * Stored but never required, and Health ships no CVX table to validate it against: the code is
     * useful for handing a record to something that speaks it, and inventing a lookup would be a
     * second vocabulary to keep current for no benefit a household can see.
     */
    val cvxCode: String?,
    /** ISO at whatever precision was recorded. */
    val givenDate: String?,
    /** Which dose in its series, when the record said. Null is ordinary and is never filled in. */
    val doseNumber: Int?,
    /** [com.health.app.logic.VaccineSource]'s key — witnessed, transcribed, recalled, unknown. */
    val source: String,
    /** Who gave it, when they are on the care team. A plain nullable id like every other link here. */
    val providerId: String?,
    /** Off the vial. Worth keeping for a recall notice, which is the only time anybody wants it. */
    val lotNumber: String?,
    /** "Left arm", "left thigh" — what an after-visit summary prints. */
    val site: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)
