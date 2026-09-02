package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.AssetAttributeEntity
import com.maintenance.app.data.db.entities.AssetEntity
import com.maintenance.app.data.db.entities.CoverageEntity
import com.maintenance.app.data.db.entities.LoanEntity
import com.maintenance.app.data.db.entities.MeterReadingEntity
import com.maintenance.app.data.db.entities.RecallEntity
import com.maintenance.app.data.db.entities.ServiceRecordEntity
import com.maintenance.app.data.db.entities.UpkeepPlanEntity
import com.maintenance.app.data.model.Asset
import com.maintenance.app.data.model.AssetCard
import com.maintenance.app.data.model.AssetDetail
import com.maintenance.app.data.model.CoverageView
import com.maintenance.app.data.model.LoanView
import com.maintenance.app.data.model.PlanView
import com.maintenance.app.data.model.RecallView
import com.maintenance.app.data.model.ServiceRecord
import com.maintenance.app.logic.AssetAttributes
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.Costs
import com.maintenance.app.logic.Coverage
import com.maintenance.app.logic.CoverageKind
import com.maintenance.app.logic.Coverages
import com.maintenance.app.logic.Docket
import com.maintenance.app.logic.DocketEntry
import com.maintenance.app.logic.DocketSource
import com.maintenance.app.logic.DueStatus
import com.maintenance.app.logic.Ledger
import com.maintenance.app.logic.LedgerAsset
import com.maintenance.app.logic.Ledgers
import com.maintenance.app.logic.ServiceEntry
import com.maintenance.app.logic.Loan
import com.maintenance.app.logic.LoanTerms
import com.maintenance.app.logic.MeterReading
import com.maintenance.app.logic.MeterState
import com.maintenance.app.logic.PlanSnapshot
import com.maintenance.app.logic.PlanKind
import com.maintenance.app.logic.PremiumPeriod
import com.maintenance.app.logic.Recall
import com.maintenance.app.logic.titleCaseComponent
import com.maintenance.app.logic.SchedulePack
import com.maintenance.app.logic.SchedulePlans
import com.maintenance.app.logic.Upkeep
import com.maintenance.app.logic.UpkeepPlan
import com.maintenance.app.logic.UpkeepStore
import com.maintenance.app.logic.VehicleFacts
import com.maintenance.app.logic.UpkeepTasks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
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
class MaintenanceRepository(private val dao: MaintenanceDao) : UpkeepStore {

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
                val meter = meterStateOf(asset.kind, readingsByAsset[row.id].orEmpty())
                val entries = docketFor(
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
                    docketFor(
                        asset = asset,
                        plans = plansByAsset[row.id].orEmpty(),
                        coverages = coveragesByAsset[row.id].orEmpty(),
                        recalls = recallsByAsset[row.id].orEmpty(),
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
                costsThisYear = Costs.summary(entries, since = now - YEAR_MILLIS, coverages = coverages, now = now),
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

    // ------------------------------------------------------------------ assets

    /**
     * Put a new asset on the register, with whatever of its kind's own fields were filled in.
     *
     * [attributes] arrives the way the form held it — every key the kind asks for, blanks included —
     * and the blanks are simply not written, which is the same rule [updateAsset] follows: an absent
     * row and an empty string must never both mean "no VIN".
     */
    suspend fun addAsset(
        name: String,
        kind: AssetKind,
        make: String? = null,
        model: String? = null,
        year: Int? = null,
        attributes: Map<String, String> = emptyMap(),
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
        val filled = attributes
            .mapNotNull { (key, value) -> kind.spec(key)?.let { it to value } }
            .mapNotNull { (spec, value) ->
                AssetAttributes.normalise(spec, value).takeIf { it.isNotBlank() }?.let { spec.key to it }
            }
        if (filled.isNotEmpty()) {
            dao.upsertAttributes(filled.map { (key, value) -> AssetAttributeEntity(id, key, value) })
        }
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

    /**
     * Delete an asset and everything under it. Returns the LifeOps tasks its plans had published,
     * which the caller takes off the week: the database cascade cannot reach into another app, and
     * "Truck: Oil change" outliving the truck by a year is exactly the kind of orphan that teaches
     * people to stop trusting a shared week.
     */
    suspend fun deleteAsset(assetId: String): List<String> {
        val published = dao.taskIdsForAsset(assetId)
        dao.deleteAsset(assetId)
        return published
    }

    // ------------------------------------------------------------------ upkeep

    suspend fun addPlan(
        assetId: String,
        title: String,
        everyDays: Int?,
        everyMeter: Long?,
        notes: String? = null,
        publishToLifeOps: Boolean = true,
        kind: PlanKind = PlanKind.UPKEEP
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
                kind = kind.key,
                publishToLifeOps = publishToLifeOps,
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
                atMeter = plan.atMeter.toMilestoneColumn(),
                active = plan.active,
                publishToLifeOps = plan.publishToLifeOps,
                updatedAt = now()
            )
        )
    }

    suspend fun setPlanActive(planId: String, active: Boolean) {
        val existing = dao.getPlan(planId) ?: return
        dao.upsertPlan(existing.copy(active = active, updatedAt = now()))
    }

    /** Delete a plan. Returns the LifeOps task it had published, for the caller to retire. */
    suspend fun deletePlan(planId: String): String? {
        val published = dao.getPlan(planId)?.lifeOpsTaskId
        dao.deletePlan(planId)
        return published
    }

    // ------------------------------------------------------------------ the LifeOps seam
    //
    // Maintenance knows when a thing is due; LifeOps is where a week is planned. So a plan puts
    // itself on that week as a task dated the day it falls due, and the tick comes back here. What
    // decides *which* of those things should happen is `logic/UpkeepTasks`; this is where the
    // decision is stored and where the tick lands.

    /** Record which LifeOps task now stands for a plan, and which occurrence it was published for. */
    override suspend fun setPlanLink(planId: String, taskId: String?, publishedDue: LocalDate?) =
        dao.setPlanLink(planId, taskId, publishedDue?.toEpochDay())

    /**
     * A plan was ticked in LifeOps: log the service, move the clock, and let go of the task.
     *
     * The record it writes is deliberately thin — no cost, no vendor — because a tick in a week
     * planner says *that* the job was done and nothing about what it involved. The note says so, so
     * a £0 line in the history reads as "not priced" rather than "free".
     *
     * For a plan with a mileage interval the **last known reading** is taken as the new baseline.
     * Without it the mileage leg would never move and the plan would be permanently overdue on one
     * of its two legs; with it the arithmetic restarts from the best number anybody has. It is
     * written onto the record but *not* filed as a new reading, because nobody read the dial.
     */
    override suspend fun completeFromWeek(planId: String, completedAt: Long): Boolean {
        val plan = dao.getPlan(planId) ?: return false

        // A prompt is not work: ticking "read the odometer" or "check recalls" writes no service
        // record and costs nothing. It just moves the prompt on — and if a reading or a check
        // arrived in the meantime, that has already moved it, which is why this is safe to run
        // twice.
        if (!PlanKind.of(plan.kind).isWork) {
            dao.upsertPlan(plan.copy(lastDoneAt = completedAt, updatedAt = now()))
            dao.setPlanLink(planId, null, null)
            return true
        }

        val baseline = if (plan.everyMeter != null || !plan.atMeter.isNullOrBlank()) {
            dao.readingsOf(plan.assetId).maxByOrNull { it.readAt }?.value
        } else {
            null
        }
        logService(
            assetId = plan.assetId,
            planId = planId,
            title = plan.title,
            vendor = null,
            performedAt = completedAt,
            costCents = 0L,
            meterValue = baseline,
            notes = COMPLETED_IN_LIFEOPS,
            recordReading = false
        )
        // The occurrence is done with; the next round publishes the next one.
        dao.setPlanLink(planId, null, null)
        return true
    }

    /**
     * Everything a publishing round needs, read once and folded rather than queried per plan.
     *
     * A plan on an asset you no longer own is reported as inactive rather than filtered out — the
     * round has to see it in order to take its task *off* the week, and a filtered-out plan would
     * leave "Truck: Oil change" sitting in a week for a truck you sold.
     */
    override suspend fun planSnapshots(now: Long): List<PlanSnapshot> {
        val assets = dao.allAssets().associateBy { it.id }
        val readings = dao.allReadings().groupBy { it.assetId }
        return dao.allPlans().mapNotNull { row ->
            val asset = assets[row.assetId] ?: return@mapNotNull null
            val kind = AssetKind.of(asset.kind)
            val meter = meterStateOf(kind, readings[asset.id].orEmpty())
            val plan = row.toPlan().let { if (asset.archived) it.copy(active = false) else it }
            PlanSnapshot(
                plan = plan,
                assetName = asset.name,
                meter = meter,
                link = row.toLink(),
                verdict = Upkeep.evaluate(plan, now, meter)
            )
        }
    }

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
        notes: String?,
        /**
         * Whether [meterValue] is also filed as a meter reading. True for work you logged by hand —
         * you read the dial. False when the figure is the *last known* reading rather than one
         * anybody took (see [completeFromLifeOps]): writing it as a reading would invent a
         * measurement, and two equal readings a month apart would then flatten the usage rate.
         */
        recordReading: Boolean = true
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
        if (recordReading && meterValue != null && meterValue > 0L) {
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

    /**
     * Apply a schedule pack to an asset: add the items it doesn't already have, touch nothing else.
     *
     * What to add is decided in `logic/SchedulePlans` — including the rule that a plan you had
     * already typed under the same title is *adopted* rather than duplicated — so this is only the
     * write. Returns what was applied, for the screen to report.
     */
    suspend fun applyPack(assetId: String, pack: SchedulePack): SchedulePlans.Application {
        val existing = dao.plansOf(assetId).map { it.toPlan() }
        val application = SchedulePlans.plan(pack, existing)
        if (application.toCreate.isEmpty()) return application

        val stamp = now()
        var order = dao.nextPlanSortOrder(assetId)
        dao.upsertPlans(
            application.toCreate.map { item ->
                val plan = SchedulePlans.toPlan(item, pack, assetId, newId(), stamp)
                UpkeepPlanEntity(
                    id = plan.id,
                    assetId = assetId,
                    title = plan.title,
                    notes = plan.notes,
                    everyDays = plan.everyDays,
                    everyMeter = plan.everyMeter,
                    atMeter = plan.atMeter.toMilestoneColumn(),
                    lastDoneAt = null,
                    lastDoneMeter = null,
                    active = true,
                    kind = plan.kind.key,
                    sourcePack = plan.sourcePack,
                    sourceItem = plan.sourceItem,
                    publishToLifeOps = true,
                    sortOrder = order++,
                    createdAt = stamp,
                    updatedAt = stamp
                )
            }
        )
        return application
    }

    /** Fill in what a VIN decode found, leaving anything you already typed exactly as it is. */
    suspend fun applyVehicleFacts(assetId: String, facts: VehicleFacts) {
        val existing = dao.getAsset(assetId) ?: return
        dao.upsertAsset(
            existing.copy(
                make = existing.make ?: facts.make,
                model = existing.model ?: facts.model,
                year = existing.year ?: facts.year,
                updatedAt = now()
            )
        )
        // Everything the decode knows that the vehicle kind has a field for — not the trim alone.
        // Each one is written only where the asset has nothing there already, because a decode is a
        // claim about a model and what you typed is a fact about your vehicle.
        val decoded = mapOf(
            ATTR_TRIM to facts.trim,
            ATTR_BODY_STYLE to facts.bodyClass,
            ATTR_ENGINE to facts.engine,
            ATTR_FUEL to facts.fuel,
            ATTR_TRANSMISSION to facts.transmission,
            ATTR_DRIVE_TYPE to facts.drive
        )
        val alreadyThere = dao.attributesOf(assetId).filter { it.value.isNotBlank() }.map { it.key }.toSet()
        val rows = decoded.mapNotNull { (key, value) ->
            value?.trim()
                ?.takeIf { it.isNotBlank() && key !in alreadyThere }
                ?.let { AssetAttributeEntity(assetId, key, it) }
        }
        if (rows.isNotEmpty()) dao.upsertAttributes(rows)
    }

    // ------------------------------------------------------------------ recalls

    /**
     * Store what NHTSA said, keeping what this household already decided about each one — and
     * satisfy any prompt that was asking for the check.
     *
     * A recall you have acknowledged stays acknowledged when the list is fetched again — the
     * campaign is the identity, and NHTSA re-sends every open campaign every time.
     *
     * Returns the LifeOps tasks the recall-check prompts had published, for the caller to tick off.
     * This is the seam running backwards, exactly as [addReading] does it: a task in a week planner
     * cannot go and ask NHTSA anything, so *running the check here* is what completes the task over
     * there. The nudge is the task; the asking is the work.
     *
     * An **empty answer still counts as a check**. "No open recalls" is the result you most want to
     * be able to trust, and a prompt that only moved on when something was wrong would ask you again
     * next week for having had nothing wrong.
     */
    suspend fun saveRecalls(assetId: String, recalls: List<Recall>, fetchedAt: Long = now()): List<String> {
        val rows = recalls.map { recall ->
            RecallEntity(
                assetId = assetId,
                campaignNumber = recall.campaignNumber,
                component = recall.component,
                summary = recall.summary,
                consequence = recall.consequence,
                remedy = recall.remedy,
                manufacturer = recall.manufacturer,
                reportedOnEpochDay = recall.reportedOn?.toEpochDay(),
                parkIt = recall.parkIt,
                parkOutside = recall.parkOutside,
                fetchedAt = fetchedAt,
                acknowledgedAt = dao.getRecall(assetId, recall.campaignNumber)?.acknowledgedAt
            )
        }
        if (rows.isNotEmpty()) dao.upsertRecalls(rows)
        dao.getAsset(assetId)?.let { dao.upsertAsset(it.copy(recallsCheckedAt = fetchedAt)) }

        val satisfied = dao.recallPromptsOf(assetId)
        satisfied.forEach { prompt ->
            dao.upsertPlan(prompt.copy(lastDoneAt = fetchedAt, updatedAt = now()))
        }
        return satisfied.mapNotNull { it.lifeOpsTaskId }
    }

    /** "Dealt with" — off the docket, still on file. Passing null puts it back. */
    suspend fun setRecallAcknowledged(assetId: String, campaign: String, acknowledged: Boolean) =
        dao.setRecallAcknowledged(assetId, campaign, if (acknowledged) now() else null)

    // ------------------------------------------------------------------ meter

    /**
     * File a reading — and satisfy any prompt that was asking for one.
     *
     * Returns the LifeOps tasks those prompts had published, for the caller to tick off. That is the
     * one place in this app where the seam runs the other way: everywhere else LifeOps announces a
     * completion and Maintenance reacts, but a task cannot carry a number, so *typing the number
     * here* is what completes the task over there. The nudge is the task; the reading is the work.
     */
    suspend fun addReading(assetId: String, value: Long, readAt: Long = now()): List<String> {
        dao.insertReading(
            MeterReadingEntity(
                id = newId(),
                assetId = assetId,
                readAt = readAt,
                value = value,
                source = READING_MANUAL
            )
        )

        val satisfied = dao.meterPromptsOf(assetId)
        satisfied.forEach { prompt ->
            dao.upsertPlan(prompt.copy(lastDoneAt = readAt, lastDoneMeter = value, updatedAt = now()))
        }
        return satisfied.mapNotNull { it.lifeOpsTaskId }
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

    private fun recallDetail(row: RecallEntity): String = when {
        row.parkIt -> "Do not drive — ${row.summary}"
        row.parkOutside -> "Do not park indoors — ${row.summary}"
        else -> row.summary
    }

    private fun RecallEntity.toRecall() = Recall(
        campaignNumber = campaignNumber,
        component = component,
        summary = summary,
        consequence = consequence,
        remedy = remedy,
        manufacturer = manufacturer,
        reportedOn = reportedOnEpochDay?.let { LocalDate.ofEpochDay(it) },
        parkIt = parkIt,
        parkOutside = parkOutside
    )

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

    private fun UpkeepPlanEntity.toLink() = UpkeepTasks.TaskLink(
        taskId = lifeOpsTaskId,
        publishedDue = publishedDueDay?.let { LocalDate.ofEpochDay(it) }
    )

    /** `"60000,120000"` → `[60000, 120000]`, sorted and forgiving of whatever ended up in the column. */
    private fun String?.toMilestones(): List<Long> =
        orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.sorted()

    private fun List<Long>.toMilestoneColumn(): String? =
        filter { it > 0 }.sorted().joinToString(",").takeIf { it.isNotEmpty() }

    private fun UpkeepPlanEntity.toPlan() = UpkeepPlan(
        id = id,
        assetId = assetId,
        title = title,
        notes = notes,
        everyDays = everyDays,
        everyMeter = everyMeter,
        atMeter = atMeter.toMilestones(),
        lastDoneAt = lastDoneAt,
        lastDoneMeter = lastDoneMeter,
        createdAt = createdAt,
        active = active,
        kind = PlanKind.of(kind),
        sourcePack = sourcePack,
        sourceItem = sourceItem,
        publishToLifeOps = publishToLifeOps
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

        /** The kind-specific attributes a VIN decode lands in; see `logic/AssetKind`. */
        const val ATTR_TRIM = "trim"
        const val ATTR_BODY_STYLE = "bodyStyle"
        const val ATTR_ENGINE = "engine"
        const val ATTR_FUEL = "fuel"
        const val ATTR_TRANSMISSION = "transmission"
        const val ATTR_DRIVE_TYPE = "driveType"

        const val READING_MANUAL = "manual"
        const val READING_FROM_SERVICE = "service"

        /** What a service record says when the tick came from the LifeOps week rather than from here. */
        const val COMPLETED_IN_LIFEOPS =
            "Ticked off in LifeOps. No cost or odometer recorded — edit this if it matters."
    }
}
