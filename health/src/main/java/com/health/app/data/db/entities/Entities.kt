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
    val note: String?
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
    val episodeId: String?
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
    val at: Long
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
