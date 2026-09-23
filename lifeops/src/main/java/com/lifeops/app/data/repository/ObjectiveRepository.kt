package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.Objective
import com.lifeops.app.data.model.ObjectiveStatus
import com.lifeops.app.data.model.ObjectiveStep
import com.lifeops.app.data.model.ObjectiveWithSteps
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.Objectives
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/** A step as the editor hands it over: no objective id or position yet, and no completion. */
data class ObjectiveStepDraft(
    /** Null for a step added in this edit; the id of the step being kept otherwise. */
    val id: String? = null,
    val title: String,
    val opensOn: String? = null,
    val afterPrevious: Boolean = false,
    val dueDate: String? = null
)

class ObjectiveRepository(private val db: LifeOpsDatabase) {
    private val dao get() = db.objectiveDao()

    /** Every objective with its steps, soonest due first. */
    fun observeAll(): Flow<List<ObjectiveWithSteps>> =
        combine(dao.observeAll(), dao.observeAllSteps()) { objectives, steps ->
            val byObjective = steps.groupBy { it.objectiveId }
            objectives.map { o ->
                ObjectiveWithSteps(
                    o.toModel(),
                    byObjective[o.id].orEmpty().map { it.toModel() }.sortedBy { it.position }
                )
            }
        }

    /**
     * Create or edit an objective and its step list in one transaction. Steps are written in the
     * order given; a kept step (non-null [ObjectiveStepDraft.id]) keeps its completion, and any step
     * the edit dropped is deleted.
     */
    suspend fun save(
        id: String?,
        title: String,
        aspectId: String?,
        dueDate: String,
        successCriteria: String,
        steps: List<ObjectiveStepDraft>
    ): String {
        val now = DateUtil.now()
        val objectiveId = id ?: UUID.randomUUID().toString()
        db.withTransaction {
            val existing = id?.let { dao.getById(it) }?.toModel()
            val objective = existing?.copy(
                title = title, aspectId = aspectId, dueDate = dueDate,
                successCriteria = successCriteria, updatedAt = now
            ) ?: Objective(
                objectiveId, title, aspectId, dueDate, successCriteria,
                ObjectiveStatus.ACTIVE, createdAt = now, updatedAt = now
            )
            dao.upsert(objective.toEntity())

            val completion = dao.getSteps(objectiveId).associate { it.id to it.completedAt }
            val written = steps.mapIndexed { index, draft ->
                val stepId = draft.id ?: UUID.randomUUID().toString()
                ObjectiveStep(
                    stepId, objectiveId, index, draft.title, draft.opensOn,
                    draft.afterPrevious, draft.dueDate, completion[stepId]
                ).also { dao.upsertStep(it.toEntity()) }
            }
            dao.deleteStepsExcept(objectiveId, written.map { it.id })
        }
        return objectiveId
    }

    /** Tick or untick a step. A step that hasn't opened yet can't be ticked. */
    suspend fun setStepDone(objectiveId: String, stepId: String, done: Boolean) {
        db.withTransaction {
            val steps = dao.getSteps(objectiveId).map { it.toModel() }
            val index = steps.indexOfFirst { it.id == stepId }
            if (index < 0) return@withTransaction
            if (done && !Objectives.isOpen(steps, index, DateUtil.todayKey())) return@withTransaction
            dao.setStepCompletedAt(stepId, if (done) DateUtil.now() else null)
        }
    }

    /**
     * Close an objective with its outcome, or reopen it with [ObjectiveStatus.ACTIVE]. Success is
     * refused while any step is still open — an objective is only complete when its steps are.
     */
    suspend fun setStatus(objectiveId: String, status: ObjectiveStatus): Boolean {
        val now = DateUtil.now()
        return db.withTransaction {
            if (status == ObjectiveStatus.SUCCEEDED) {
                val steps = dao.getSteps(objectiveId).map { it.toModel() }
                if (!Objectives.canReportSuccess(steps)) return@withTransaction false
            }
            val closedAt = if (status == ObjectiveStatus.ACTIVE) null else now
            dao.setStatus(objectiveId, status.value, closedAt, now)
            true
        }
    }

    suspend fun delete(objectiveId: String) = dao.delete(objectiveId)
}
