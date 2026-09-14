package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.UpkeepPlanEntity
import com.maintenance.app.logic.PlanKind
import com.maintenance.app.logic.UpkeepPlan

/**
 * Upkeep plans: what is owed on an asset, how often, and whether it is still wanted.
 */
class UpkeepPlanStore(
    private val dao: MaintenanceDao
) {

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
}
