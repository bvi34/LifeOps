package com.maintenance.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Maintenance's tables.
 *
 * Seven of them, one asset id threaded through six. Everything below an asset cascades with it: a
 * house you sold cannot leave its mortgage, its filter schedule and eleven service records behind
 * to be found later by a query that forgot to filter. That is the whole of the schema's structure,
 * and it is deliberately boring — the interesting decisions are two:
 *
 * **The kind-specific fields are rows, not columns.** A VIN, a parcel number and a serial number
 * live in `asset_attributes` keyed by the spec in `logic/AssetKind`, so adding "Boat" later does not
 * add a `hullNumber` column that is null for every car in the table. What stays a real column on
 * `assets` is what every kind has: a name, a make, a year, what it cost, what it is worth.
 *
 * **Money is cents and rates are basis points, everywhere.** A mortgage balance in floating point
 * is a mortgage balance that drifts, and the app's entire claim is that these numbers match your
 * statement.
 *
 * Nothing here is synced. Maintenance is not a peer on the suite's sync spine and does not read
 * another app's database, so there is no `syncVersion` column anywhere in this file.
 */

/**
 * One thing you own.
 *
 * [kind] is a string rather than an ordinal so a kind can be added, renamed or reordered without
 * rewriting everyone's rows — and an unknown one resolves to `OTHER` rather than throwing, so a row
 * from a future version still opens.
 *
 * [currentValueCents] is what it is worth *now*, typed by hand and stale by design: nothing here
 * knows what a 2009 Odyssey is worth this month, and a number the app invented would be worse than
 * the one you last looked up. It exists so equity against a loan can be shown at all.
 */
@Entity(
    tableName = "assets",
    indices = [Index("kind"), Index("archived"), Index("sortOrder")]
)
data class AssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** `logic/AssetKind.key`. */
    val kind: String,
    val make: String?,
    val model: String?,
    /** The model year. Null means unknown, which is not the same as year zero. */
    val year: Int?,
    val purchasedAt: Long?,
    val purchasePriceCents: Long?,
    val currentValueCents: Long?,
    val notes: String?,
    val colorArgb: Long,
    /**
     * When NHTSA was last asked about recalls on this vehicle. Null means never asked.
     *
     * Stored so the answer is not re-fetched every time the screen is opened: a recall list changes
     * a handful of times a decade, and an app that hits a government API on every glance is an app
     * that deserves to be rate-limited.
     */
    val recallsCheckedAt: Long? = null,
    /** Sold, scrapped or given away: off the docket, still in the history. */
    val archived: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One kind-specific fact about an asset — the VIN, the parcel number, the filter size.
 *
 * Keyed by (asset, key) so writing the VIN twice updates it rather than growing a second one, and
 * so a value can be cleared by deleting the row instead of storing an empty string that every read
 * then has to treat as absent.
 */
@Entity(
    tableName = "asset_attributes",
    primaryKeys = ["assetId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId")]
)
data class AssetAttributeEntity(
    val assetId: String,
    /** `logic/AssetAttributeSpec.key`. */
    val key: String,
    val value: String
)

/**
 * A standing job: the thing that comes round again.
 *
 * [everyDays] and [everyMeter] may both be set — "every 5,000 miles or 6 months, whichever comes
 * first" is how service schedules are actually written — and both may be null, which makes the row
 * a checklist entry rather than a schedule.
 *
 * [lastDoneAt] and [lastDoneMeter] are written by logging the work, never by a reset button. A
 * clock that can be cleared without recording what was done is how a service history ends up with
 * holes in it.
 */
@Entity(
    tableName = "upkeep_plans",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId"), Index("active"), Index("sortOrder")]
)
data class UpkeepPlanEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val title: String,
    val notes: String?,
    val everyDays: Int?,
    val everyMeter: Long?,
    /**
     * Absolute odometer milestones, ascending, comma-separated — `"60000,120000,180000"`.
     *
     * A string rather than a table because it is a *value* of the plan, not a thing with an
     * identity: nothing ever points at one milestone, and a schedule has three of them, not three
     * hundred. The repository owns the parse; see `logic/UpkeepPlan.atMeter` for what it means.
     */
    val atMeter: String? = null,
    val lastDoneAt: Long?,
    val lastDoneMeter: Long?,
    val active: Boolean,
    /** `logic/PlanKind.key` — work to be done, or a prompt to read the meter. */
    @ColumnInfo(defaultValue = "upkeep")
    val kind: String = "upkeep",
    /**
     * Where this plan came from, when it wasn't typed by hand: the schedule pack's id and the item's
     * key within it. Applying a pack again matches on this pair, which is what lets a second apply
     * add only what is new without touching anything already tuned.
     */
    val sourcePack: String? = null,
    val sourceItem: String? = null,
    /**
     * Whether this plan puts itself on the LifeOps week as a task dated the day it falls due.
     * Defaults to on — a schedule nobody is reminded of is a schedule nobody keeps.
     */
    @ColumnInfo(defaultValue = "1")
    val publishToLifeOps: Boolean = true,
    /**
     * The LifeOps task currently standing for this plan, if there is one.
     *
     * A **soft link into another app's database**, deliberately unenforceable: LifeOps can delete
     * the task, and a week close can replace it with a carried-forward copy under a new id. Both
     * are handled by re-reading rather than by a constraint — see `logic/UpkeepTasks`.
     */
    val lifeOpsTaskId: String? = null,
    /**
     * The due *day* (epoch day) this plan was last published for.
     *
     * Kept even after [lifeOpsTaskId] is dropped, which is the whole point: it is how the app knows
     * a task it published was deleted on purpose, and stops itself putting the same one back.
     */
    val publishedDueDay: Long? = null,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One thing that was done, and what it cost.
 *
 * [planId] is a **soft link** — declared without a foreign key on purpose. Deleting the schedule
 * you no longer follow must not delete the six times you did it: the record stands on its own, and
 * a dangling plan id simply reads as an unscheduled job.
 */
@Entity(
    tableName = "service_records",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId"), Index("planId"), Index("performedAt")]
)
data class ServiceRecordEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    /** The plan this satisfied, when it satisfied one. Soft link; may dangle. */
    val planId: String?,
    val title: String,
    val vendor: String?,
    val performedAt: Long,
    val costCents: Long,
    /** The meter reading at the time, when the asset wears one. */
    val meterValue: Long?,
    val notes: String?,
    val createdAt: Long
)

/**
 * One reading off an odometer or hour meter.
 *
 * Readings are kept rather than a single "current mileage" being overwritten, because two readings
 * are what turn "every 5,000 miles" into a date. See `logic/Meter`.
 */
@Entity(
    tableName = "meter_readings",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId"), Index("readAt")]
)
data class MeterReadingEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val readAt: Long,
    val value: Long,
    /** "manual" or "service" — where the number came from, so a history reads honestly. */
    val source: String
)

/**
 * What is owed on an asset: a mortgage, a car note, a family loan.
 *
 * Everything derived — balance, interest paid, payoff date, equity — is computed from these columns
 * by `logic/Loan` rather than stored, so a balance shown in this app is never a number somebody
 * forgot to update. The one figure that genuinely can go stale is [escrowCents], which is why it is
 * its own column and is labelled as what it is rather than folded into the payment.
 *
 * [accountRef] is meant to hold the last four digits, and the UI says so. A full account number is
 * not a thing an offline app needs in order to tell you what you owe.
 */
@Entity(
    tableName = "loans",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId")]
)
data class LoanEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    /** "Mortgage", "Car loan" — what you call it, not what the app calls it. */
    val label: String,
    val lender: String?,
    /** Last four digits, by convention and by hint text. */
    val accountRef: String?,
    val principalCents: Long,
    /** Basis points: 6.25% is 625. Never a double; this compounds three hundred and sixty times. */
    val annualRateBps: Int,
    val termMonths: Int,
    /** What is actually paid, when it differs from the note's own payment. Null means "as written". */
    val paymentCents: Long?,
    /** Taxes and insurance collected with the payment. Not debt; never amortised. */
    val escrowCents: Long,
    val startEpochDay: Long?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/** A policy, warranty, registration or inspection — the paperwork that expires. */
@Entity(
    tableName = "coverages",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId"), Index("expiresAt")]
)
data class CoverageEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    /** `logic/CoverageKind.key`. */
    val kind: String,
    val provider: String,
    val policyNumber: String?,
    val premiumCents: Long,
    /** `logic/PremiumPeriod.key`. */
    val period: String,
    val startsAt: Long?,
    val expiresAt: Long?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * A safety recall on a vehicle, as NHTSA published it and as this household stands with it.
 *
 * Kept rather than re-fetched because the useful state is local: whether *you* have dealt with it.
 * [acknowledgedAt] is that — "this one is done, or was done before I owned it" — and it takes the
 * recall off the docket while leaving it on file, which is the same distinction the app makes
 * everywhere else between finished and gone.
 *
 * The primary key is the asset and the campaign together, so re-asking NHTSA updates the row it
 * already has rather than growing a second copy of a recall that has been open since 2019.
 */
@Entity(
    tableName = "recalls",
    primaryKeys = ["assetId", "campaignNumber"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId"), Index("acknowledgedAt")]
)
data class RecallEntity(
    val assetId: String,
    /** NHTSA's campaign number — "19V680000". The identity of a recall everywhere. */
    val campaignNumber: String,
    val component: String,
    val summary: String,
    val consequence: String?,
    val remedy: String?,
    val manufacturer: String?,
    val reportedOnEpochDay: Long?,
    /** NHTSA's "do not drive". */
    val parkIt: Boolean,
    /** NHTSA's "do not park indoors". */
    val parkOutside: Boolean,
    val fetchedAt: Long,
    /** When you marked it dealt with. Null while it is still owed. */
    val acknowledgedAt: Long?
)
