package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Health's tables.
 *
 * Two conventions run through all of them, and both are deliberate:
 *
 *  - **Instants are epoch millis, dates are ISO strings.** When a temperature was taken is a moment
 *    — it is compared, subtracted and windowed by `logic/`, so it is a `Long`. A birth date is a
 *    calendar fact that must not shift when someone changes time zone, so it is `yyyy-MM-dd` text.
 *  - **Every row belongs to a profile.** There is no "current person" hiding in a global; the
 *    profile id is on the row, because the whole point of this app is more than one person.
 *
 * Cross-table links (`episodeId`, `medicationId`) are plain nullable ids rather than Room foreign
 * keys: a reading taken before anyone declared an illness is still a real reading, and deleting a
 * medication should not delete the record that a dose of it was given. The profile link is the one
 * exception — see the cascade in [ProfileEntity]'s note.
 */

/**
 * One person being tracked. [colorArgb] is how they're told apart at a glance across every screen,
 * and [baselineTempC] is their own normal when they know it (some people simply run at 36.4, and a
 * 37.6 means more for them than the population threshold suggests).
 *
 * Deleting a profile deletes that person's readings, symptoms, doses, episodes and notes with it —
 * see the cascade queries in the DAO. "Delete this person's data" has to mean it.
 */
@Entity(tableName = "profiles", indices = [Index("name"), Index("syncVersion")])
data class ProfileEntity(
    @PrimaryKey val id: String,
    /**
     * The identity this person keeps across the People sync seam, as distinct from [id], which is
     * only this database's row id. Null until the seam stamps one.
     */
    val personKey: String? = null,
    /** Bumped by every local edit, left alone by every write that arrived over the seam. */
    val syncVersion: Long = 0L,
    val name: String,
    /** Free text — "Me", "Daughter", "Mum". Not an enum; households don't fit one. */
    val relationship: String?,
    /** ISO `yyyy-MM-dd`, or null. Drives the age-aware fever rules, so it is worth asking for. */
    val birthDate: String?,
    val colorArgb: Long,
    val baselineTempC: Double?,
    /**
     * Allergies, conditions, the doctor's number — whatever you'd want in front of you at 3am.
     *
     * **Never published over the People seam**, even though People has a field of the same name.
     * They are not the same field: People's note is "likes hiking, hates crowds", and this one is
     * medical. Mapping one onto the other would quietly copy a person's conditions into the
     * household directory and from there into LifeOps — which is exactly the kind of leak a shared
     * wire makes easy and nobody asked for. See `HealthRepository.toPacket`.
     */
    val notes: String?,
    /**
     * Whether the directory counts this person as a **household member** — which is what decides
     * whether Health grows a profile for them at all (see `HealthSyncService`).
     *
     * Health holds a copy rather than deriving it from "do I have a profile?", because the two can
     * legitimately disagree: un-ticking somebody in People stops them being *offered* to Health and
     * never deletes what Health already recorded, so a profile can outlive the flag. Storing the
     * answer is also what stops Health's own next packet flipping the directory's tick back on.
     */
    val household: Boolean = true,
    val sortOrder: Int,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A profile Health has removed, kept only long enough to say so across the People sync seam.
 *
 * Removing somebody in Health means "stop tracking their health", not "remove them from the
 * household" — so unlike LifeOps' tombstone this one does **not** publish a withdrawal. It publishes
 * `household = false`: the directory un-ticks them, and the next round stops offering them back.
 *
 * Without it the removal simply doesn't stick. Health's profile is created *from* the directory's
 * tick, so the first time that person is edited in People their packet comes round again above the
 * cursor and Health dutifully re-creates the profile that was just deleted. A tombstone is the only
 * thing left to speak for a row that has gone.
 */
@Entity(tableName = "profile_tombstones", indices = [Index("syncVersion")])
data class ProfileTombstoneEntity(
    @PrimaryKey val personKey: String,
    val name: String,
    val removedAt: Long,
    val syncVersion: Long
)

/**
 * One measurement. [type] says which; [value] is always in the canonical unit for that type
 * (temperature in °C, weight in kg, pressure in mmHg), with [secondaryValue] carrying diastolic for
 * blood pressure and nothing else. [site] is only meaningful for temperature and is what makes an
 * armpit reading comparable to an ear one.
 */
@Entity(
    tableName = "readings",
    indices = [Index("profileId"), Index("takenAt"), Index("type"), Index("episodeId")]
)
data class ReadingEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val episodeId: String?,
    val type: String,
    val value: Double,
    val secondaryValue: Double?,
    val site: String?,
    val takenAt: Long,
    val note: String?,
    val createdAt: Long
)

/** One symptom, from when it started until it stops ([endedAt] null while it's still going). */
@Entity(
    tableName = "symptoms",
    indices = [Index("profileId"), Index("episodeId"), Index("startedAt")]
)
data class SymptomEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val episodeId: String?,
    val name: String,
    /** 1–5, mild to severe. A number you can chart beats an adjective you can't. */
    val severity: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val note: String?,
    /** When the row was written, as against when the symptom started. See the note in [DoseEntity]. */
    val createdAt: Long? = null
)

/**
 * A medicine as it is kept for one person, with the limits from its own label. Every limit is
 * nullable because bottles differ, and Health will not invent a restriction nobody wrote down.
 *
 * This row is **one person's use of a product**, not the product itself and not the bottle. The
 * product's own facts live once in [DrugFactsEntity] (reached by [rxcui]); the bottle in the
 * cupboard lives once in [CabinetItemEntity] (reached by [cabinetItemId]). The separation is what
 * lets two children take the same bottle at different doses without either dose being a copy of the
 * other — and what stops a monograph being duplicated per person.
 */
@Entity(
    tableName = "medications",
    indices = [Index("profileId"), Index("name"), Index("rxcui"), Index("cabinetItemId")]
)
data class MedicationEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val name: String,
    /** As printed: "160 mg / 5 mL". Kept as text because that's how labels read. */
    val strength: String?,
    val form: String?,
    val doseAmount: Double?,
    val doseUnit: String,
    val minIntervalHours: Double?,
    val maxDosesPer24h: Int?,
    val maxAmountPer24h: Double?,
    val note: String?,
    val active: Boolean,
    val createdAt: Long,
    /** The RxNorm concept this was identified as, when it was added by lookup rather than typed. */
    val rxcui: String? = null,
    /** The cabinet item this is given from, when the household is tracking the stock. */
    val cabinetItemId: String? = null,
    /** [com.health.app.logic.ReminderMode]'s key. Everything that predates reminders is `"off"`. */
    val reminderMode: String = "off",
    /** `"08:00,20:00"` for a set-times reminder; null otherwise. */
    val reminderTimes: String? = null
)

/**
 * One dose actually given. [medicationName] is denormalised on purpose: the history of what someone
 * was given must survive the medicine being renamed or deleted from the list.
 */
@Entity(
    tableName = "doses",
    indices = [Index("profileId"), Index("medicationId"), Index("takenAt"), Index("episodeId")]
)
data class DoseEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val medicationId: String?,
    val medicationName: String,
    val amount: Double,
    val unit: String,
    val takenAt: Long,
    val note: String?,
    val episodeId: String?,
    /**
     * When this row was *written*, as distinct from [takenAt], when the dose was given.
     *
     * They differ whenever somebody fills the history in afterwards — the 2am dose typed up over
     * breakfast — and the difference is worth keeping. A record made at the time and a record made
     * from memory are both worth having and are not equally reliable, and the history says which is
     * which rather than presenting a reconstruction as an observation.
     *
     * Null on every row written before the column existed: Health does not know when those were
     * entered, and says so rather than assuming. Readings have carried this since v1 under the same
     * name.
     */
    val createdAt: Long? = null
)

/**
 * A bout of illness — the thing readings, symptoms and doses hang off so they can be read back as
 * one story instead of a scatter of rows. Open while [endedAt] is null; one open episode per person
 * at a time is enforced by the repository, not the schema.
 */
@Entity(tableName = "episodes", indices = [Index("profileId"), Index("startedAt")])
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * The care log: fluids taken, a bath, a doctor's call, what they said. This is the "and such" of
 * looking after someone — the part you cannot reconstruct afterwards and always wish you had.
 */
@Entity(
    tableName = "care_notes",
    indices = [Index("profileId"), Index("episodeId"), Index("at")]
)
data class CareNoteEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val episodeId: String?,
    val kind: String,
    val text: String,
    val at: Long,
    /** When the row was written, as against when it happened. See the note in [DoseEntity]. */
    val createdAt: Long? = null
)

/**
 * A product Health has looked up, cached whole.
 *
 * Keyed by RxNorm's concept id rather than by a row id, and **not** scoped to a profile: what
 * acetaminophen oral suspension is made of does not vary by who is taking it. One lookup serves
 * every person in the house, one refresh updates all of them, and a household with the same bottle
 * on two people's lists stores the label once.
 *
 * The fields are the monograph flattened — see `logic/DrugFacts`. [sectionsJson] holds the label's
 * own sections as written; the flat columns beside it are the handful of facts a list row needs
 * without deserialising a page of text.
 *
 * [fetchedAt] is when Health asked. [labelEffectiveTime] is when the manufacturer last revised the
 * label. Both are shown, because a stale cache and a stale label are different problems.
 */
@Entity(tableName = "drug_facts")
data class DrugFactsEntity(
    @PrimaryKey val rxcui: String,
    val name: String,
    val genericName: String?,
    val brandName: String?,
    val doseForm: String?,
    /** Comma-separated, in the order the source gave them. Lists this short don't earn a table. */
    val routes: String?,
    val ingredients: String?,
    val availableStrengths: String?,
    val schedule: String?,
    val productType: String?,
    val manufacturer: String?,
    val labelSetId: String?,
    /** openFDA's `effective_time`, as the `yyyyMMdd` string it arrives as. */
    val labelEffectiveTime: String?,
    /** The label sections, JSON-encoded. Opaque to SQL; read back by the repository. */
    val sectionsJson: String?,
    val sources: String?,
    val fetchedAt: Long
)

/**
 * One thing physically in the medicine cabinet.
 *
 * Deliberately **not** scoped to a profile. A bottle of ibuprofen is a household possession, not a
 * fact about a person — one bottle serves whoever needs it, and duplicating it per person would mean
 * four rows to keep in step and four expiry dates to get wrong. Who takes what, and at what dose,
 * stays in [MedicationEntity], which points here.
 *
 * Everything except the name is optional. A cabinet somebody has to fill in completely is a cabinet
 * that stays empty; "there's Calpol in the bathroom" is already worth recording.
 */
@Entity(tableName = "cabinet_items", indices = [Index("name"), Index("rxcui"), Index("expiryDate")])
data class CabinetItemEntity(
    @PrimaryKey val id: String,
    /** The looked-up product, when it was added by search. Null for a hand-typed item. */
    val rxcui: String?,
    val name: String,
    val brandName: String?,
    /** As printed: "160 mg / 5 mL". */
    val strength: String?,
    val form: String?,
    /** How much is left, in [quantityUnit] — stock, not a dose. */
    val quantity: Double?,
    val quantityUnit: String,
    /** ISO `yyyy-MM-dd`, or `yyyy-MM` for the month-only dates most boxes print. */
    val expiryDate: String?,
    /** "Kitchen cupboard", "the nappy bag" — where it actually is, which is half the point. */
    val location: String?,
    /** Tell me it's running low at this much left. Null means "when it can't cover a dose". */
    val lowStockThreshold: Double?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

// --- coverage and care team -----------------------------------------------------------------------
//
// Five tables that between them answer the two questions the medical half of this app never could:
// **who pays for this**, and **who do we take her to**. They follow the same split the cabinet
// established — the thing itself is household-scoped, the person's use of it is not — because a
// family policy and a family doctor are both single objects that several people share.

/**
 * One insurance policy, as copied off the card.
 *
 * Household-scoped like [CabinetItemEntity], and for the same reason: a family plan is *one* policy
 * with one carrier, one group number and one set of phone numbers on the back, and duplicating it
 * per person would be four rows to keep in step and three of them out of date by renewal. What
 * varies per person — the member number, the person code, who the subscriber is — lives on
 * [InsuranceMemberEntity], which points here.
 *
 * **Health stores a card, not a policy.** There is no column for a deductible, a copay, a
 * coinsurance share or an out-of-pocket maximum, and that is deliberate. Those are the terms of a
 * legal document that runs to eighty pages and changes by service; an app that let you type "$30
 * copay" into a box would be inviting somebody to plan around a number nobody checked. What is here
 * is what is printed on the card and useful at a desk.
 *
 * [directoryUrl] is what the plan published; [directoryBaseUrl] is the endpoint that actually
 * answered when Health probed it, which is usually not the same string — see
 * `logic/ProviderDirectory`. Both are kept: the first is what the user pasted and can correct, the
 * second is what the next check should use.
 *
 * The card images are file *names*, not blobs. See [InsuranceMemberEntity] for why.
 */
@Entity(
    tableName = "insurance_plans",
    indices = [Index("carrierName"), Index("archived")]
)
data class InsurancePlanEntity(
    @PrimaryKey val id: String,
    val carrierName: String,
    val planName: String?,
    /** [com.health.app.logic.CoverageKind]'s key — medical, dental, vision, pharmacy. */
    val coverageKind: String,
    /** [com.health.app.logic.PlanType]'s key — HMO, PPO, and the rest of what cards print. */
    val planType: String,
    val groupNumber: String?,
    val payerId: String?,
    val rxBin: String?,
    val rxPcn: String?,
    val rxGroup: String?,
    val memberServicesPhone: String?,
    val nurseLinePhone: String?,
    /** ISO `yyyy-MM-dd`. A card with neither date is coverage Health declines to judge. */
    val effectiveDate: String?,
    val endDate: String?,
    /** The provider directory address as published — what the user pasted, kept as they pasted it. */
    val directoryUrl: String?,
    /** The FHIR base that actually answered. Written by the probe, not by the form. */
    val directoryBaseUrl: String?,
    /** [com.health.app.logic.DirectoryOutcome]'s key, from the last probe. */
    val directoryStatus: String?,
    val directoryCheckedAt: Long?,
    val directoryDetail: String?,
    /** File names under `insurance-cards/`, not blobs. See [InsuranceMemberEntity]. */
    val frontImagePath: String?,
    val backImagePath: String?,
    val note: String?,
    /**
     * Last year's plan, kept rather than deleted. A policy that has ended is still the policy that
     * covered a visit in November, and the network checks recorded against it are still evidence.
     */
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One person's membership of one plan — their own number on the household's policy.
 *
 * This is the row that makes a family plan work: four people, one [InsurancePlanEntity], four member
 * ids and four person codes. It is also the row that lets a household hold two plans at once
 * (primary and secondary, or a parent's medical and an employer's dental) without either becoming a
 * duplicate of the other.
 *
 * ### Why the card photos are file names
 *
 * [frontImagePath] and [backImagePath] hold a **file name** under Health's `insurance-cards/`
 * directory, never image bytes. A card photo is a couple of megabytes; a database that carries four
 * of them is a database that is copied, WAL-checkpointed and backed up in full every time anybody
 * records a temperature. The files sit beside the database, are carried by the same backup, and are
 * deleted with the row that names them — see `data/store/CardImageStore`.
 *
 * The plan's own images are the fallback: photograph the one card that came in the post, attach it
 * to the policy, and everybody on it has a card. A member's own images override that for the
 * households where each person's card really is different.
 */
@Entity(
    tableName = "insurance_members",
    indices = [Index("profileId"), Index("planId")]
)
data class InsuranceMemberEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val planId: String,
    val memberId: String?,
    /** The two-digit suffix that tells a family plan's members apart. Printed as "Person code"/"Dep #". */
    val personCode: String?,
    val subscriberName: String?,
    val relationshipToSubscriber: String?,
    /** When *this person's* cover started, when it differs from the policy's own dates. */
    val effectiveDate: String?,
    val endDate: String?,
    /** Which card gets handed over first when somebody carries two. */
    val primaryCoverage: Boolean = true,
    val frontImagePath: String?,
    val backImagePath: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A doctor, dentist, therapist or practice the household sees.
 *
 * **Household-scoped, and deliberately not owned by an insurance plan.** That separation is the
 * whole point of the row. A doctor is a person you have a relationship with; a plan is a contract
 * you renew every January, and it is entirely ordinary for the plan to change while the doctor
 * doesn't. If the care team hung off the policy, changing carriers would mean re-entering every
 * clinician in the house — and, worse, would throw away the history of network checks that is the
 * only way to notice that the new plan doesn't cover the paediatrician the old one did.
 *
 * [npi] is the National Provider Identifier, and it is the single most valuable field here: it is
 * unique to one clinician nationally, so a directory search on it is exact where a name search is a
 * guess. It is validated (ten digits, Luhn over the `80840` prefix) before it is ever sent, because
 * a mistyped NPI and a doctor who has left the network both come back as no results.
 */
@Entity(tableName = "providers", indices = [Index("name"), Index("npi")])
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val npi: String?,
    val specialty: String?,
    /** The group or clinic they practise under — what a directory calls the organization. */
    val practiceName: String?,
    val phone: String?,
    val addressLine: String?,
    val website: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Which person sees which provider, and in what capacity.
 *
 * Its own table rather than a column on either side, because the relationship is many-to-many in
 * both directions and both directions actually happen: one paediatrician is primary for three
 * children, and one child has a paediatrician, a dentist and an allergist. [role] is *this person's*
 * relationship to that provider — the same clinician can be somebody's primary and somebody else's
 * specialist.
 */
@Entity(
    tableName = "provider_links",
    indices = [Index("profileId"), Index("providerId")]
)
data class ProviderLinkEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val providerId: String,
    /** [com.health.app.data.model.CareRole]'s key. */
    val role: String,
    /** ISO `yyyy-MM-dd` — since when they've been seeing them, when anybody knows. */
    val since: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One network check: what a directory said about one provider under one plan, at one moment.
 *
 * **Kept as an append-only history, never overwritten**, and that is the design rather than an
 * accident of it. A single "in network" flag cannot tell the difference between a doctor who was
 * never in the network and one who was in it until March, and that difference is the most useful
 * thing this feature produces. `logic/NetworkStatus` derives the verdict from the whole list; delete
 * the old rows and the verdict quietly degrades to "not listed" for both cases.
 *
 * A check with no [planId] is one made against no particular policy — which happens when somebody
 * rings the office and asks. The outcome column carries those too ([com.health.app.logic.CheckOutcome]),
 * because "a human was told this on the phone" is evidence, and evidence with a date on it belongs
 * in the same history as everything else.
 */
@Entity(
    tableName = "network_checks",
    indices = [Index("providerId"), Index("planId"), Index("checkedAt")]
)
data class NetworkCheckEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    /** Null for a check that wasn't about a specific policy — a phone call, usually. */
    val planId: String?,
    val checkedAt: Long,
    /** [com.health.app.logic.CheckOutcome]'s key. */
    val outcome: String,
    /** The carrier, as it read at the time. A household changes plans; the old checks stay true. */
    val directoryLabel: String?,
    val directoryUrl: String?,
    /** The name the directory had, when it differs from the one the household wrote down. */
    val matchedName: String?,
    val matchedNpi: String?,
    /** How many entries matched. More than one is the whole basis of the "couldn't tell them apart" verdict. */
    val matchCount: Int,
    /** The networks the listing named, comma-separated. Lists this short don't earn a table. */
    val networks: String?,
    val detail: String?
)
