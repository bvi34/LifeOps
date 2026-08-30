package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.AssetAttributeEntity
import com.maintenance.app.data.db.entities.AssetEntity
import com.maintenance.app.data.db.entities.CoverageEntity
import com.maintenance.app.data.db.entities.LoanEntity
import com.maintenance.app.data.db.entities.MeterReadingEntity
import com.maintenance.app.data.db.entities.ServiceRecordEntity
import com.maintenance.app.data.db.entities.UpkeepPlanEntity
import com.maintenance.app.data.model.Asset
import com.maintenance.app.data.model.AssetCard
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.data.model.CoverageView
import com.maintenance.app.data.model.LoanView
import com.maintenance.app.data.model.PlanView
import com.maintenance.app.data.model.ServiceRecord
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.Costs
import com.maintenance.app.logic.Coverage
import com.maintenance.app.logic.CoverageKind
import com.maintenance.app.logic.Coverages
import com.maintenance.app.logic.Docket
import com.maintenance.app.logic.DocketEntry
import com.maintenance.app.logic.DocketSource
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.logic.Loan
import com.maintenance.app.logic.LoanTerms
import com.maintenance.app.logic.MeterReading
import com.maintenance.app.logic.MeterState
import com.maintenance.app.logic.PremiumPeriod
import com.maintenance.app.logic.Upkeep
import com.maintenance.app.logic.UpkeepPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.util.UUID

/**
 * Maintenance's one repository: entity rows in, logic types out, and every write that spans more
 * than one row.
 *
 * Two rules run through the file.
 *
 * **Nothing derived is stored.** A balance, a due date, a cost per mile and an asset's status are
 * all computed on read, from `logic/`. There is no `nextDueAt` column to go stale while the phone
 * is in a drawer, no "overdue" flag to be wrong the morning after, and nothing for a restore to
 * leave inconsistent. The cost is recomputing a fold over a few hundred rows whenever a screen
 * collects, which at household scale is nothing.
 *
 * **Logging the work is the only way to move a clock.** [logService] is the single place that
 * advances a plan's `lastDoneAt`/`lastDoneMeter`, and it does so by writing the record that says
 * what was done. There is deliberately no "mark done without recording it" path: a schedule that
 * can be silently reset is a service history with holes in it.
 *
 * The clock is read at fold time rather than injected. That means a docket recomputes its "due in
 * 3 days" when the data changes or the screen re-collects, not on a timer — which is exactly right
 * for a list you look at rather than watch.
 */
class MaintenanceRepository(private val dao: MaintenanceDao) {

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    // ------------------------------------------------------------------ reads

    /**
     * Every asset with the one line that says where it stands.
     *
     * Six tables are read whole and folded here rather than queried per asset: a household has a
     * dozen assets, and a query per asset per table would be seventy round trips to draw one list.
     */
    fun observeAssetCards(): Flow<List<AssetCard>> {
        val things = combine(dao.observeAssets(), dao.observeAttributes(), dao.observeReadings(), ::Triple)
        val owed = combine(dao.observePlans(), dao.observeCoverages(), dao.observeLoans(), ::Triple)
        return combine(things, owed) { (assets, attributes, readings), (plans, coverages, loans) ->
            val now = now()
            val attributesByAsset = attributes.groupBy { it.assetId }
            val readingsByAsset = readings.groupBy { it.assetId }
            val plansByAsset = plans.groupBy { it.assetId }
            val coveragesByAsset = coverages.groupBy { it.assetId }
            val loansByAsset = loans.groupBy { it.assetId }

            assets.map { row ->
                val asset = row.toAsset(attributesByAsset[row.id].orEmpty())
                val meter = meterStateOf(asset.kind, readingsByAsset[row.id].orEmpty())
                val entries = docketFor(
                    asset = asset,
                    plans = plansByAsset[row.id].orEmpty(),
                    coverages = coveragesByAsset[row.id].orEmpty(),
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
     * The docket: everything owed across every asset, ordered.
     *
     * Archived assets are left out. A truck you sold does not need its registration renewing, and a
     * docket that says otherwise is one you learn to scroll past.
     */
    fun observeDocket(): Flow<List<DocketEntry>> {
        val things = combine(dao.observeAssets(), dao.observeAttributes(), dao.observeReadings(), ::Triple)
        val owed = combine(dao.observePlans(), dao.observeCoverages()) { plans, coverages -> plans to coverages }
        return combine(things, owed) { (assets, attributes, readings), (plans, coverages) ->
            val now = now()
            val attributesByAsset = attributes.groupBy { it.assetId }
            val readingsByAsset = readings.groupBy { it.assetId }
            val plansByAsset = plans.groupBy { it.assetId }
            val coveragesByAsset = coverages.groupBy { it.assetId }

            Docket.order(
                assets.filterNot { it.archived }.flatMap { row ->
                    val asset = row.toAsset(attributesByAsset[row.id].orEmpty())
                    docketFor(
                        asset = asset,
                        plans = plansByAsset[row.id].orEmpty(),
                        coverages = coveragesByAsset[row.id].orEmpty(),
                        meter = meterStateOf(asset.kind, readingsByAsset[row.id].orEmpty()),
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
        return combine(core, rest, dao.observeAttributesFor(assetId)) { (row, planRows, recordRows), (readingRows, loanRows, coverageRows), attributeRows ->
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
                    PlanView(model, Upkeep.evaluate(model, now, meter))
                },
                records = records,
                readings = readings,
                loans = loanRows.map { it.toView(asset.currentValueCents, now) },
                coverages = coverages.map { CoverageView(it, Coverages.status(it, now), Coverages.summary(it, now)) },
                meter = meter,
                costsThisYear = Costs.summary(entries, since = now - YEAR_MILLIS, coverages = coverages, now = now),
                costsAllTime = Costs.summary(entries),
                perYearCents = Costs.perYear(entries, now),
                centsPerMeterUnit = Costs.centsPerMeterUnit(entries, readings)
            )
        }
    }

    /** The kinds present, so the list can offer the filters that would actually match something. */
    fun observeKindsInUse(): Flow<List<AssetKind>> =
        dao.observeAssets().map { rows -> rows.map { AssetKind.of(it.kind) }.distinct() }

    // ------------------------------------------------------------------ assets

    suspend fun addAsset(
        name: String,
        kind: AssetKind,
        make: String? = null,
        model: String? = null,
        year: Int? = null,
        colorArgb: Long = DEFAULT_COLOR
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertAsset(
            AssetEntity(
                id = id,
                name = name.trim(),
                kind = kind.key,
                make = make?.trim()?.takeIf { it.isNotBlank() },
                model = model?.trim()?.takeIf { it.isNotBlank() },
                year = year,
                purchasedAt = null,
                purchasePriceCents = null,
                currentValueCents = null,
                notes = null,
                colorArgb = colorArgb,
                archived = false,
                sortOrder = dao.nextAssetSortOrder(),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    /**
     * Save the asset and its kind-specific fields in one go.
     *
     * [attributes] is the complete set the form held, blanks included: a key whose value came back
     * empty is *deleted* rather than stored as an empty string, so a cleared VIN reads as absent
     * everywhere instead of as a value that happens to be blank.
     */
    suspend fun updateAsset(asset: Asset, attributes: Map<String, String>) {
        val existing = dao.getAsset(asset.id) ?: return
        dao.upsertAsset(
            existing.copy(
                name = asset.name.trim(),
                kind = asset.kind.key,
                make = asset.make?.trim()?.takeIf { it.isNotBlank() },
                model = asset.model?.trim()?.takeIf { it.isNotBlank() },
                year = asset.year,
                purchasedAt = asset.purchasedAt,
                purchasePriceCents = asset.purchasePriceCents,
                currentValueCents = asset.currentValueCents,
                notes = asset.notes?.trim()?.takeIf { it.isNotBlank() },
                colorArgb = asset.colorArgb,
                archived = asset.archived,
                updatedAt = now()
            )
        )
        val (kept, cleared) = attributes.entries.partition { it.value.isNotBlank() }
        if (kept.isNotEmpty()) {
            dao.upsertAttributes(kept.map { AssetAttributeEntity(asset.id, it.key, it.value.trim()) })
        }
        if (cleared.isNotEmpty()) {
            dao.deleteAttributes(asset.id, cleared.map { it.key })
        }
    }

    suspend fun setArchived(assetId: String, archived: Boolean) {
        val existing = dao.getAsset(assetId) ?: return
        dao.upsertAsset(existing.copy(archived = archived, updatedAt = now()))
    }

    suspend fun deleteAsset(assetId: String) = dao.deleteAsset(assetId)

    // ------------------------------------------------------------------ upkeep

    suspend fun addPlan(
        assetId: String,
        title: String,
        everyDays: Int?,
        everyMeter: Long?,
        notes: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertPlan(
            UpkeepPlanEntity(
                id = id,
                assetId = assetId,
                title = title.trim(),
                notes = notes?.trim()?.takeIf { it.isNotBlank() },
                everyDays = everyDays?.takeIf { it > 0 },
                everyMeter = everyMeter?.takeIf { it > 0 },
                lastDoneAt = null,
                lastDoneMeter = null,
                active = true,
                sortOrder = dao.nextPlanSortOrder(assetId),
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun updatePlan(plan: UpkeepPlan) {
        val existing = dao.getPlan(plan.id) ?: return
        dao.upsertPlan(
            existing.copy(
                title = plan.title.trim(),
                notes = plan.notes?.trim()?.takeIf { it.isNotBlank() },
                everyDays = plan.everyDays?.takeIf { it > 0 },
                everyMeter = plan.everyMeter?.takeIf { it > 0 },
                active = plan.active,
                updatedAt = now()
            )
        )
    }

    suspend fun setPlanActive(planId: String, active: Boolean) {
        val existing = dao.getPlan(planId) ?: return
        dao.upsertPlan(existing.copy(active = active, updatedAt = now()))
    }

    suspend fun deletePlan(planId: String) = dao.deletePlan(planId)

    /**
     * Record work done — and, when it satisfied a plan, move that plan's clock.
     *
     * This is the only write that touches `lastDoneAt`/`lastDoneMeter`, and it is why the app has
     * no "reset" button. A meter value given here is also filed as a reading, because the mileage
     * at an oil change is exactly as good a data point as one typed at a petrol pump — and because
     * a mileage interval cannot be dated without them.
     */
    suspend fun logService(
        assetId: String,
        planId: String?,
        title: String,
        vendor: String?,
        performedAt: Long,
        costCents: Long,
        meterValue: Long?,
        notes: String?
    ) {
        val stamp = now()
        dao.upsertRecord(
            ServiceRecordEntity(
                id = newId(),
                assetId = assetId,
                planId = planId,
                title = title.trim(),
                vendor = vendor?.trim()?.takeIf { it.isNotBlank() },
                performedAt = performedAt,
                costCents = costCents,
                meterValue = meterValue,
                notes = notes?.trim()?.takeIf { it.isNotBlank() },
                createdAt = stamp
            )
        )
        if (meterValue != null && meterValue > 0L) {
            dao.insertReading(
                MeterReadingEntity(
                    id = newId(),
                    assetId = assetId,
                    readAt = performedAt,
                    value = meterValue,
                    source = READING_FROM_SERVICE
                )
            )
        }
        if (planId != null) {
            dao.getPlan(planId)?.let { plan ->
                dao.upsertPlan(
                    plan.copy(
                        lastDoneAt = performedAt,
                        lastDoneMeter = meterValue ?: plan.lastDoneMeter,
                        updatedAt = stamp
                    )
                )
            }
        }
    }

    suspend fun deleteRecord(recordId: String) = dao.deleteRecord(recordId)

    // ------------------------------------------------------------------ meter

    suspend fun addReading(assetId: String, value: Long, readAt: Long = now()) {
        dao.insertReading(
            MeterReadingEntity(
                id = newId(),
                assetId = assetId,
                readAt = readAt,
                value = value,
                source = READING_MANUAL
            )
        )
    }

    suspend fun deleteReading(readingId: String) = dao.deleteReading(readingId)

    // ------------------------------------------------------------------ money

    suspend fun upsertLoan(
        loanId: String?,
        assetId: String,
        label: String,
        lender: String?,
        accountRef: String?,
        principalCents: Long,
        annualRateBps: Int,
        termMonths: Int,
        paymentCents: Long?,
        escrowCents: Long,
        startEpochDay: Long?,
        notes: String?
    ): String {
        val stamp = now()
        val id = loanId ?: newId()
        dao.upsertLoan(
            LoanEntity(
                id = id,
                assetId = assetId,
                label = label.trim().ifBlank { "Loan" },
                lender = lender?.trim()?.takeIf { it.isNotBlank() },
                accountRef = accountRef?.trim()?.takeIf { it.isNotBlank() },
                principalCents = principalCents,
                annualRateBps = annualRateBps,
                termMonths = termMonths,
                paymentCents = paymentCents?.takeIf { it > 0L },
                escrowCents = escrowCents,
                startEpochDay = startEpochDay,
                notes = notes?.trim()?.takeIf { it.isNotBlank() },
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun deleteLoan(loanId: String) = dao.deleteLoan(loanId)

    suspend fun upsertCoverage(
        coverageId: String?,
        assetId: String,
        kind: CoverageKind,
        provider: String,
        policyNumber: String?,
        premiumCents: Long,
        period: PremiumPeriod,
        startsAt: Long?,
        expiresAt: Long?,
        notes: String?
    ): String {
        val stamp = now()
        val id = coverageId ?: newId()
        dao.upsertCoverage(
            CoverageEntity(
                id = id,
                assetId = assetId,
                kind = kind.key,
                provider = provider.trim(),
                policyNumber = policyNumber?.trim()?.takeIf { it.isNotBlank() },
                premiumCents = premiumCents,
                period = period.key,
                startsAt = startsAt,
                expiresAt = expiresAt,
                notes = notes?.trim()?.takeIf { it.isNotBlank() },
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun deleteCoverage(coverageId: String) = dao.deleteCoverage(coverageId)

    // ------------------------------------------------------------------ folding

    /** Every line one asset puts on the docket: its plans, then its paperwork. */
    private fun docketFor(
        asset: Asset,
        plans: List<UpkeepPlanEntity>,
        coverages: List<CoverageEntity>,
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
        return fromPlans + fromCoverages
    }

    private fun meterStateOf(kind: AssetKind, rows: List<MeterReadingEntity>): MeterState? =
        MeterState.from(kind.meter, rows.map { MeterReading(it.readAt, it.value) })

    // ------------------------------------------------------------------ mapping

    private fun AssetEntity.toAsset(attributes: List<AssetAttributeEntity>) = Asset(
        id = id,
        name = name,
        kind = AssetKind.of(kind),
        make = make,
        model = model,
        year = year,
        purchasedAt = purchasedAt,
        purchasePriceCents = purchasePriceCents,
        currentValueCents = currentValueCents,
        notes = notes,
        colorArgb = colorArgb,
        archived = archived,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt,
        attributes = attributes.associate { it.key to it.value }
    )

    private fun UpkeepPlanEntity.toPlan() = UpkeepPlan(
        id = id,
        assetId = assetId,
        title = title,
        notes = notes,
        everyDays = everyDays,
        everyMeter = everyMeter,
        lastDoneAt = lastDoneAt,
        lastDoneMeter = lastDoneMeter,
        createdAt = createdAt,
        active = active
    )

    private fun ServiceRecordEntity.toRecord() = ServiceRecord(
        id = id,
        assetId = assetId,
        planId = planId,
        title = title,
        vendor = vendor,
        performedAt = performedAt,
        costCents = costCents,
        meterValue = meterValue,
        notes = notes
    )

    private fun CoverageEntity.toCoverage() = Coverage(
        id = id,
        assetId = assetId,
        kind = CoverageKind.of(kind),
        provider = provider,
        policyNumber = policyNumber,
        premiumCents = premiumCents,
        period = PremiumPeriod.of(period),
        startsAt = startsAt,
        expiresAt = expiresAt,
        notes = notes
    )

    private fun LoanEntity.terms() = LoanTerms(
        principalCents = principalCents,
        annualRateBps = annualRateBps,
        termMonths = termMonths,
        paymentCents = paymentCents,
        escrowCents = escrowCents
    )

    private fun LoanEntity.snapshotAt(now: Long) =
        Loan.snapshot(terms(), startEpochDay, epochDay(now))

    private fun LoanEntity.toView(valueCents: Long?, now: Long): LoanView {
        val terms = terms()
        val snapshot = Loan.snapshot(terms, startEpochDay, epochDay(now))
        return LoanView(
            id = id,
            assetId = assetId,
            label = label,
            lender = lender,
            accountRef = accountRef,
            terms = terms,
            startEpochDay = startEpochDay,
            notes = notes,
            snapshot = snapshot,
            payoffDate = Loan.payoffDate(terms, startEpochDay),
            equityCents = Loan.equityCents(valueCents, snapshot.balanceCents)
        )
    }

    /** Loans are counted in months, so their arithmetic works in local days, not milliseconds. */
    private fun epochDay(millis: Long): Long =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    companion object {
        private const val YEAR_MILLIS = 365L * 86_400_000L

        /** A neutral slate; an asset's colour is picked when it is added and edited any time. */
        const val DEFAULT_COLOR = 0xFF64748BL

        const val READING_MANUAL = "manual"
        const val READING_FROM_SERVICE = "service"
    }
}
