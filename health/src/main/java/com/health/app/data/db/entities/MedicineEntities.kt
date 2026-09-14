package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Medicines, the doses taken of them, the bottles they come from, and the drug facts looked
 * up about them.
 */

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
