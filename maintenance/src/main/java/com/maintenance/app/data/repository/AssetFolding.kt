package com.maintenance.app.data.repository

import com.maintenance.app.data.db.entities.CoverageEntity
import com.maintenance.app.data.db.entities.MeterReadingEntity
import com.maintenance.app.data.db.entities.RecallEntity
import com.maintenance.app.data.db.entities.UpkeepPlanEntity
import com.maintenance.app.data.model.Asset
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.Coverages
import com.maintenance.app.logic.DocketEntry
import com.maintenance.app.logic.DocketSource
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.logic.MeterReading
import com.maintenance.app.logic.MeterState
import com.maintenance.app.logic.Recall
import com.maintenance.app.logic.Upkeep
import java.time.LocalDate
import java.time.ZoneId
import com.maintenance.app.logic.titleCaseComponent

/**
 * Six tables folded into the one line a screen shows.
 *
 * Its own class rather than methods on [AssetBoardStore] because [WeekHandoffStore] folds the same
 * way when it decides what a published task is worth, and two foldings that are meant to agree are
 * two foldings that eventually will not. Stateless and pure: rows in, a verdict out, no database and
 * no clock of its own.
 */
class AssetFolding {

    /** Every line one asset puts on the docket: its plans, then its paperwork. */
    fun docketFor(
        asset: Asset,
        plans: List<UpkeepPlanEntity>,
        coverages: List<CoverageEntity>,
        recalls: List<RecallEntity>,
        meter: MeterState?,
        now: Long
    ): List<DocketEntry> {
        val fromPlans = plans.map { row ->
            val plan = row.toPlan()
            val verdict = Upkeep.evaluate(plan, now, meter)
            DocketEntry(
                id = plan.id,
                assetId = asset.id,
                assetName = asset.name,
                title = plan.title,
                detail = verdict.summary,
                status = verdict.status,
                dueAt = verdict.dueAt,
                source = DocketSource.UPKEEP
            )
        }
        val fromCoverages = coverages.map { row ->
            val coverage = row.toCoverage()
            DocketEntry(
                id = coverage.id,
                assetId = asset.id,
                assetName = asset.name,
                title = "${coverage.kind.label} · ${coverage.provider}",
                detail = Coverages.summary(coverage, now),
                status = Coverages.status(coverage, now),
                dueAt = coverage.expiresAt,
                source = DocketSource.COVERAGE
            )
        }
        /*
         * Recalls, and the judgement in how loudly they speak.
         *
         * NHTSA's two flags — do not drive, do not park indoors — are an emergency, so they come
         * through as overdue and sit at the top of the docket. Everything else is *scheduled*: a
         * vehicle can carry a decade of open campaigns, most of them already done by somebody, and
         * fourteen red lines on the day you add a used truck is a docket you stop reading. They are
         * still on the list, still on the asset's page, and still counted — just not shouted.
         */
        val fromRecalls = recalls.filter { it.acknowledgedAt == null }.map { row ->
            DocketEntry(
                id = row.campaignNumber,
                assetId = asset.id,
                assetName = asset.name,
                title = "Recall · ${row.component.titleCaseComponent().ifBlank { row.campaignNumber }}",
                detail = recallDetail(row),
                status = if (row.parkIt || row.parkOutside) DueStatus.OVERDUE else DueStatus.SCHEDULED,
                // A recall has no due date; the day it was reported is the only date it has, and it
                // is what orders one urgent recall against another.
                dueAt = row.reportedOnEpochDay?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
                source = DocketSource.RECALL
            )
        }

        return fromPlans + fromCoverages + fromRecalls
    }

    fun meterStateOf(kind: AssetKind, rows: List<MeterReadingEntity>): MeterState? =
        MeterState.from(kind.meter, rows.map { MeterReading(it.readAt, it.value) })
}
