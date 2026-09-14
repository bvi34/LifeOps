package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.model.AssetCard
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.data.model.CoverageView
import com.maintenance.app.data.model.PlanView
import com.maintenance.app.data.model.RecallView
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.Costs
import com.maintenance.app.logic.Coverages
import com.maintenance.app.logic.Docket
import com.maintenance.app.logic.DocketEntry
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.logic.Ledger
import com.maintenance.app.logic.LedgerAsset
import com.maintenance.app.logic.Ledgers
import com.maintenance.app.logic.Loan
import com.maintenance.app.logic.MeterReading
import com.maintenance.app.logic.MeterState
import com.maintenance.app.logic.ServiceEntry
import com.maintenance.app.logic.Upkeep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Everything the screens read: the asset list, one asset in full, and the upkeep due across
 * the household.

 * Reads only. Six tables are read whole and folded by [AssetFolding] rather than queried per asset —
 * a household has a dozen assets, and a query per asset per table would be seventy round trips to
 * draw one list.
 */
class AssetBoardStore(
    private val dao: MaintenanceDao,
    private val folding: AssetFolding
) {

    /**
     * Every asset with the one line that says where it stands.
     *
     * Six tables are read whole and folded here rather than queried per asset: a household has a
     * dozen assets, and a query per asset per table would be seventy round trips to draw one list.
     */
    fun observeAssetCards(): Flow<List<AssetCard>> {
        val things = combine(dao.observeAssets(), dao.observeAttributes(), dao.observeReadings(), ::Triple)
        val owed = combine(dao.observePlans(), dao.observeCoverages(), dao.observeLoans(), ::Triple)
        return combine(things, owed, dao.observeRecalls()) { (assets, attributes, readings), (plans, coverages, loans), recalls ->
            val now = now()
            val attributesByAsset = attributes.groupBy { it.assetId }
            val readingsByAsset = readings.groupBy { it.assetId }
            val plansByAsset = plans.groupBy { it.assetId }
            val coveragesByAsset = coverages.groupBy { it.assetId }
            val loansByAsset = loans.groupBy { it.assetId }
            val recallsByAsset = recalls.groupBy { it.assetId }

            assets.map { row ->
                val asset = row.toAsset(attributesByAsset[row.id].orEmpty())
                val meter = folding.meterStateOf(asset.kind, readingsByAsset[row.id].orEmpty())
                val entries = folding.docketFor(
                    asset = asset,
                    plans = plansByAsset[row.id].orEmpty(),
                    coverages = coveragesByAsset[row.id].orEmpty(),
                    recalls = recallsByAsset[row.id].orEmpty(),
                    meter = meter,
                    now = now
                )
                val balances = loansByAsset[row.id].orEmpty().map { it.snapshotAt(now).balanceCents }
                AssetCard(
                    asset = asset,
                    meter = meter,
                    nextDue = Docket.order(entries).firstOrNull { it.status != DueStatus.DORMANT },
                    openPlans = plansByAsset[row.id].orEmpty().count { it.active },
                    overdue = entries.count { it.status == DueStatus.OVERDUE },
                    balanceCents = balances.takeIf { it.isNotEmpty() }?.sum()
                )
            }
        }
    }

    /**
     * The ledger: what the whole register costs, owes and is worth over a window.
     *
     * [since] null is everything ever. The window is the caller's because it is a question somebody
     * asks two ways — "what has this year cost" and "what has this ever cost" — and the answer to
     * both is the same fold over the same rows.
     *
     * Loan balances are worked out here rather than in `logic/Ledgers`, because the arithmetic is
     * `logic/Loan`'s and doing it in two places is how two screens end up disagreeing about a
     * balance.
     */
    fun observeLedger(since: Long?): Flow<Ledger> =
        combine(
            dao.observeAssets(),
            dao.observeRecords(),
            dao.observeCoverages(),
            dao.observeLoans()
        ) { assets, records, coverages, loans ->
            val now = now()
            val loansByAsset = loans.groupBy { it.assetId }
            Ledgers.of(
                assets = assets.map { row ->
                    LedgerAsset(
                        id = row.id,
                        name = row.name,
                        kind = AssetKind.of(row.kind),
                        worthCents = row.currentValueCents,
                        owedCents = loansByAsset[row.id].orEmpty()
                            .sumOf { it.snapshotAt(now).balanceCents.coerceAtLeast(0L) },
                        archived = row.archived,
                        colorArgb = row.colorArgb
                    )
                },
                entries = records.map { it.toRecord().asEntry() },
                coverages = coverages.groupBy { it.assetId }
                    .mapValues { (_, rows) -> rows.map { it.toCoverage() } },
                since = since,
                now = now
            )
        }

    /**
     * Every service ever logged, as bare entries.
     *
     * This exists for one question — *who did the brakes last time?* — which only has an answer
     * across assets: the garage that did the truck is the one you would ring about the mower. See
     * `logic/Vendors`.
     */
    fun observeServiceEntries(): Flow<List<ServiceEntry>> =
        dao.observeRecords().map { rows -> rows.map { it.toRecord().asEntry() } }

    /**
     * The docket: everything owed across every asset, ordered.
     *
     * Archived assets are left out. A truck you sold does not need its registration renewing, and a
     * docket that says otherwise is one you learn to scroll past.
     */
    fun observeDocket(): Flow<List<DocketEntry>> {
        val things = combine(dao.observeAssets(), dao.observeAttributes(), dao.observeReadings(), ::Triple)
        val owed = combine(dao.observePlans(), dao.observeCoverages(), dao.observeRecalls(), ::Triple)
        return combine(things, owed) { (assets, attributes, readings), (plans, coverages, recalls) ->
            val now = now()
            val attributesByAsset = attributes.groupBy { it.assetId }
            val readingsByAsset = readings.groupBy { it.assetId }
            val plansByAsset = plans.groupBy { it.assetId }
            val coveragesByAsset = coverages.groupBy { it.assetId }
            val recallsByAsset = recalls.groupBy { it.assetId }

            Docket.order(
                assets.filterNot { it.archived }.flatMap { row ->
                    val asset = row.toAsset(attributesByAsset[row.id].orEmpty())
                    folding.docketFor(
                        asset = asset,
                        plans = plansByAsset[row.id].orEmpty(),
                        coverages = coveragesByAsset[row.id].orEmpty(),
                        recalls = recallsByAsset[row.id].orEmpty(),
                        meter = folding.meterStateOf(asset.kind, readingsByAsset[row.id].orEmpty()),
                        now = now
                    )
                }
            )
        }
    }

    /** One asset and everything hanging off it. Null once the asset is deleted. */
    fun observeAssetDetail(assetId: String): Flow<AssetDetail?> {
        val core = combine(
            dao.observeAsset(assetId),
            dao.observePlansFor(assetId),
            dao.observeRecordsFor(assetId),
            ::Triple
        )
        val rest = combine(
            dao.observeReadingsFor(assetId),
            dao.observeLoansFor(assetId),
            dao.observeCoveragesFor(assetId),
            ::Triple
        )
        val extras = combine(dao.observeAttributesFor(assetId), dao.observeRecallsFor(assetId)) { attrs, recalls -> attrs to recalls }
        return combine(core, rest, extras) { (row, planRows, recordRows), (readingRows, loanRows, coverageRows), (attributeRows, recallRows) ->
            if (row == null) return@combine null
            val now = now()
            val asset = row.toAsset(attributeRows)
            val readings = readingRows.map { MeterReading(it.readAt, it.value) }
            val meter = MeterState.from(asset.kind.meter, readings)
            val records = recordRows.map { it.toRecord() }
            val entries = records.map { it.asEntry() }
            val coverages = coverageRows.map { it.toCoverage() }

            AssetDetail(
                asset = asset,
                plans = planRows.map { plan ->
                    val model = plan.toPlan()
                    PlanView(model, Upkeep.evaluate(model, now, meter), plan.toLink())
                },
                records = records,
                readings = readings,
                loans = loanRows.map { it.toView(asset.currentValueCents, now) },
                coverages = coverages.map { CoverageView(it, Coverages.status(it, now), Coverages.summary(it, now)) },
                meter = meter,
                costsThisYear = Costs.summary(entries, since = now - MaintenanceRepository.YEAR_MILLIS, coverages = coverages, now = now),
                costsAllTime = Costs.summary(entries),
                perYearCents = Costs.perYear(entries, now),
                centsPerMeterUnit = Costs.centsPerMeterUnit(entries, readings),
                recalls = recallRows.map { RecallView(it.toRecall(), acknowledged = it.acknowledgedAt != null) },
                recallsCheckedAt = row.recallsCheckedAt
            )
        }
    }

    /** The kinds present, so the list can offer the filters that would actually match something. */
    fun observeKindsInUse(): Flow<List<AssetKind>> =
        dao.observeAssets().map { rows -> rows.map { AssetKind.of(it.kind) }.distinct() }
}
