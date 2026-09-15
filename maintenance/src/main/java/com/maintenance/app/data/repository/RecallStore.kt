package com.maintenance.app.data.repository

import com.maintenance.app.data.db.dao.MaintenanceDao
import com.maintenance.app.data.db.entities.RecallEntity
import com.maintenance.app.logic.Recall
import kotlinx.coroutines.flow.map

/**
 * Safety recalls against an asset, and whether each has been dealt with.
 */
class RecallStore(
    private val dao: MaintenanceDao
) {

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
}
