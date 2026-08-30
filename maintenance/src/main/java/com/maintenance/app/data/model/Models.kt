package com.maintenance.app.data.model

import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.Coverage
import com.maintenance.app.logic.CostSummary
import com.maintenance.app.logic.DocketEntry
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.logic.DueVerdict
import com.maintenance.app.logic.LoanSnapshot
import com.maintenance.app.logic.LoanTerms
import com.maintenance.app.logic.MeterReading
import com.maintenance.app.logic.MeterState
import com.maintenance.app.logic.Recall
import com.maintenance.app.logic.ServiceEntry
import com.maintenance.app.logic.UpkeepPlan
import com.maintenance.app.logic.UpkeepTasks
import java.time.LocalDate

/**
 * What the screens work in: entity rows with their string columns resolved into enums, and the
 * derived figures already worked out.
 *
 * The shapes with logic behind them — `UpkeepPlan`, `Coverage`, `MeterReading`, `ServiceEntry` —
 * are declared in `logic/` rather than here, so the due arithmetic, the amortisation and the cost
 * rollups stay unit-testable on the JVM. What lives in this file is the assembly: an asset with its
 * attributes attached, and the per-screen bundles the repository folds together.
 */

/** One thing you own, with its kind-specific fields resolved. */
data class Asset(
    val id: String,
    val name: String,
    val kind: AssetKind,
    val make: String?,
    val model: String?,
    val year: Int?,
    val purchasedAt: Long?,
    val purchasePriceCents: Long?,
    val currentValueCents: Long?,
    val notes: String?,
    val colorArgb: Long,
    val archived: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val attributes: Map<String, String> = emptyMap()
) {
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"

    fun attribute(key: String): String? = attributes[key]?.takeIf { it.isNotBlank() }

    /**
     * "2003 Honda Accord" — year, make and model, skipping whichever of them is unknown.
     *
     * Blank when none of the three is filled in, so a caller can fall back to the kind's own label
     * rather than drawing an empty line under the name.
     */
    val descriptor: String
        get() = listOfNotNull(
            year?.takeIf { it > 0 }?.toString(),
            make?.takeIf { it.isNotBlank() },
            model?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
}

/**
 * One upkeep plan with the app's verdict on it, and whatever LifeOps currently holds for it.
 *
 * The link is on the view rather than on the plan because it is not something you edit — it is the
 * seam's own bookkeeping, and the screen shows it as a line of prose ("on the week for 31 Mar")
 * rather than as a field.
 */
data class PlanView(
    val plan: UpkeepPlan,
    val verdict: DueVerdict,
    val link: UpkeepTasks.TaskLink = UpkeepTasks.TaskLink.NONE
)

/**
 * One recall, and where this household stands with it.
 *
 * [acknowledged] is local and is the only part of a recall this app owns: NHTSA says what is open
 * for the model, you say whether it has been dealt with on *your* vehicle.
 */
data class RecallView(val recall: Recall, val acknowledged: Boolean)

/** One coverage with where its expiry stands today. */
data class CoverageView(val coverage: Coverage, val status: DueStatus, val summary: String)

/** One loan with everything derived from it: balance, interest, payoff, equity. */
data class LoanView(
    val id: String,
    val assetId: String,
    val label: String,
    val lender: String?,
    val accountRef: String?,
    val terms: LoanTerms,
    val startEpochDay: Long?,
    val notes: String?,
    val snapshot: LoanSnapshot,
    val payoffDate: LocalDate?,
    /** Against this asset's stated value, when it has one. Null when nothing is claimed. */
    val equityCents: Long?
)

/**
 * An asset as the list shows it: the one line that says where it stands, and nothing that would
 * need loading its whole history.
 */
data class AssetCard(
    val asset: Asset,
    val meter: MeterState?,
    /** The most pressing thing owed on it, or null when nothing is. */
    val nextDue: DocketEntry?,
    val openPlans: Int,
    val overdue: Int,
    /** What is still owed across every loan on it. Null when there are none. */
    val balanceCents: Long?
) {
    val status: DueStatus get() = nextDue?.status ?: DueStatus.SCHEDULED
}

/** Everything about one asset — what the detail screen holds. */
data class AssetDetail(
    val asset: Asset,
    val plans: List<PlanView>,
    val records: List<ServiceRecord>,
    val readings: List<MeterReading>,
    val loans: List<LoanView>,
    val coverages: List<CoverageView>,
    val meter: MeterState?,
    val costsThisYear: CostSummary,
    val costsAllTime: CostSummary,
    /** Spend per year, or null while the history is too short to divide honestly. */
    val perYearCents: Long?,
    /** Cents per mile or hour, or null when the meter cannot say how far it went. */
    val centsPerMeterUnit: Double?,
    val recalls: List<RecallView> = emptyList(),
    /** When NHTSA was last asked. Null means never — which the screen says rather than hides. */
    val recallsCheckedAt: Long? = null
) {
    val openRecalls: Int get() = recalls.count { !it.acknowledged }
    val urgentRecalls: Int get() = recalls.count { !it.acknowledged && it.recall.isUrgent }
}

/** One logged job. The cost-only shape the rollups use is `logic/ServiceEntry`. */
data class ServiceRecord(
    val id: String,
    val assetId: String,
    val planId: String?,
    val title: String,
    val vendor: String?,
    val performedAt: Long,
    val costCents: Long,
    val meterValue: Long?,
    val notes: String?
) {
    fun asEntry(): ServiceEntry = ServiceEntry(id, assetId, performedAt, costCents, meterValue)
}

