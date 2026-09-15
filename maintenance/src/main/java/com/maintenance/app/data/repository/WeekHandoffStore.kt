package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.AssetAttributeEntity
import com.maintenance.app.data.db.entities.MeterReadingEntity
import com.maintenance.app.data.db.entities.ServiceRecordEntity
import com.maintenance.app.data.db.entities.UpkeepPlanEntity
import com.maintenance.app.logic.AssetKind
import com.maintenance.app.logic.PlanKind
import com.maintenance.app.logic.PlanSnapshot
import com.maintenance.app.logic.SchedulePack
import com.maintenance.app.logic.SchedulePlans
import com.maintenance.app.logic.Upkeep
import com.maintenance.app.logic.UpkeepStore
import com.maintenance.app.logic.VehicleFacts
import java.time.LocalDate
import kotlinx.coroutines.flow.map

/**
 * The seam to the LifeOps week: publishing owed upkeep as a task, hearing back when the
 * task is ticked, and writing the service record that results.
 *
 * Implements [UpkeepStore] because `logic/UpkeepTasks` drives the hand-off and should not know that
 * a database is on the other side of it.
 */
class WeekHandoffStore(
    private val dao: MaintenanceDao,
    private val folding: AssetFolding
) : UpkeepStore {

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
            notes = MaintenanceRepository.COMPLETED_IN_LIFEOPS,
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
            val meter = folding.meterStateOf(kind, readings[asset.id].orEmpty())
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
                    source = MaintenanceRepository.READING_FROM_SERVICE
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
            MaintenanceRepository.ATTR_TRIM to facts.trim,
            MaintenanceRepository.ATTR_BODY_STYLE to facts.bodyClass,
            MaintenanceRepository.ATTR_ENGINE to facts.engine,
            MaintenanceRepository.ATTR_FUEL to facts.fuel,
            MaintenanceRepository.ATTR_TRANSMISSION to facts.transmission,
            MaintenanceRepository.ATTR_DRIVE_TYPE to facts.drive
        )
        val alreadyThere = dao.attributesOf(assetId).filter { it.value.isNotBlank() }.map { it.key }.toSet()
        val rows = decoded.mapNotNull { (key, value) ->
            value?.trim()
                ?.takeIf { it.isNotBlank() && key !in alreadyThere }
                ?.let { AssetAttributeEntity(assetId, key, it) }
        }
        if (rows.isNotEmpty()) dao.upsertAttributes(rows)
    }
}
