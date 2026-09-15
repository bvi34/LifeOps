package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.logic.PlanSnapshot
import com.maintenance.app.logic.UpkeepStore
import java.time.LocalDate

/**
 * Maintenance's composition root: one object the screens are handed, holding one store per thing the
 * app keeps track of about the household's things.
 *
 * This class decides nothing. It owns no queries and no timestamps — it wires the stores together
 * and hands them out. One collaborator is built here because it belongs to no single store:
 * [AssetFolding], the six-tables-into-one-line fold that both the board and the LifeOps seam do, and
 * which two copies of would eventually disagree.
 *
 * It implements [UpkeepStore] by forwarding to [week], so `logic/UpkeepTasks` can drive the hand-off
 * without knowing a database is on the other side of it.
 */
class MaintenanceRepository(dao: MaintenanceDao) : UpkeepStore {

    private val folding = AssetFolding()

    val board = AssetBoardStore(dao, folding)
    val assets = AssetStore(dao)
    val upkeep = UpkeepPlanStore(dao)
    val week = WeekHandoffStore(dao, folding)
    val recalls = RecallStore(dao)
    val meter = MeterStore(dao)
    val money = MoneyStore(dao)

    // `logic/UpkeepTasks` is handed this object as its UpkeepStore; the week is what answers.
    override suspend fun planSnapshots(now: Long): List<PlanSnapshot> = week.planSnapshots(now)

    override suspend fun setPlanLink(planId: String, taskId: String?, publishedDue: LocalDate?) =
        week.setPlanLink(planId, taskId, publishedDue)

    override suspend fun completeFromWeek(planId: String, completedAt: Long): Boolean =
        week.completeFromWeek(planId, completedAt)

    companion object {
        internal const val YEAR_MILLIS = 365L * 86_400_000L

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
