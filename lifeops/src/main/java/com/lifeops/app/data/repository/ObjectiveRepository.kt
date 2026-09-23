package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.db.entities.TaskEntity
import com.lifeops.app.data.model.Objective
import com.lifeops.app.data.model.ObjectiveNote
import com.lifeops.app.data.model.ObjectiveStatus
import com.lifeops.app.data.model.ObjectiveStep
import com.lifeops.app.data.model.ObjectiveWithSteps
import com.lifeops.app.data.model.Task
import com.lifeops.app.data.model.TaskSource
import com.lifeops.app.data.model.TaskStatus
import com.lifeops.app.data.model.Week
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.ImportParser
import com.lifeops.app.util.Objectives
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
            // A dropped step's tasks go back to being ordinary tasks: their notes and time stay.
            val dropped = completion.keys - written.map { it.id }.toSet()
            if (dropped.isNotEmpty()) db.taskDao().unlinkObjectiveSteps(dropped.toList())
            dao.deleteStepsExcept(objectiveId, written.map { it.id })
            // Keep the step's open week task in step with the edit: same title, due date, aspect.
            val byId = written.associateBy { it.id }
            if (byId.isNotEmpty()) {
                db.taskDao().getByObjectiveSteps(byId.keys.toList())
                    .filter { it.status == TaskStatus.PENDING.value || it.status == TaskStatus.QUEUED.value }
                    .forEach { task ->
                        val step = byId[task.objectiveStepId] ?: return@forEach
                        val updated = task.copy(title = step.title, dueDate = step.dueDate, aspectId = aspectId)
                        if (updated != task) db.taskDao().update(updated)
                    }
            }
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
            val now = DateUtil.now()
            dao.setStepCompletedAt(stepId, if (done) now else null)
            // And the step's task on the open week follows, so the board and the card agree.
            val weekId = db.weekDao().getCurrentWeek()?.id ?: return@withTransaction
            db.taskDao().getByObjectiveSteps(listOf(stepId))
                .filter { it.weekId == weekId }
                .forEach { task ->
                    if (done && task.status == TaskStatus.PENDING.value) {
                        db.taskDao().markCompleted(task.id, TaskStatus.COMPLETED.value, now)
                    } else if (!done && task.status == TaskStatus.COMPLETED.value) {
                        db.taskDao().unmarkCompleted(task.id)
                    }
                }
        }
    }

    /**
     * Put an objective's open steps on [week] as tasks — the ones due by the week's end, and any
     * already being worked on (see [Objectives.belongsOnWeek]). A task is how a step gets notes,
     * photos, time and the timer, and how it sits under its objective on the board. Idempotent: a
     * step with a task of any status in [week] already is left alone, so a skipped one stays skipped.
     */
    suspend fun syncWeek(week: Week, today: String = DateUtil.todayKey()) {
        if (week.isClosed) return
        db.withTransaction {
            val active = dao.getAll().filter { it.status == ObjectiveStatus.ACTIVE.value }
            if (active.isEmpty()) return@withTransaction
            val stepsByObjective = dao.getAllSteps().map { it.toModel() }.groupBy { it.objectiveId }
            val allStepIds = active.flatMap { o -> stepsByObjective[o.id].orEmpty().map { it.id } }
            if (allStepIds.isEmpty()) return@withTransaction
            val linked = db.taskDao().getByObjectiveSteps(allStepIds)
            val worked = linked.mapNotNull { it.objectiveStepId }.toSet()
            val onWeek = linked.filter { it.weekId == week.id }.mapNotNull { it.objectiveStepId }.toSet()
            val now = DateUtil.now()
            for (objective in active) {
                val steps = stepsByObjective[objective.id].orEmpty().sortedBy { it.position }
                steps.forEachIndexed { index, step ->
                    if (step.id in onWeek) return@forEachIndexed
                    if (!Objectives.belongsOnWeek(steps, index, week.endDate, today, step.id in worked)) return@forEachIndexed
                    db.taskDao().upsert(stepTask(step, objective.aspectId, week.id, now))
                }
            }
        }
    }

    /**
     * Start work on an open step this week, ahead of its due date — gives it a task (so notes,
     * photos and time) on the current week. Returns that task's id, existing or new; null when
     * the step isn't open or there's no week to put it on.
     */
    suspend fun workOnThisWeek(stepId: String): String? = db.withTransaction {
        val week = db.weekDao().getCurrentWeek() ?: return@withTransaction null
        val step = dao.getAllSteps().firstOrNull { it.id == stepId }?.toModel() ?: return@withTransaction null
        db.taskDao().getByObjectiveSteps(listOf(stepId)).firstOrNull { it.weekId == week.id }
            ?.let { return@withTransaction it.id }
        val objective = dao.getById(step.objectiveId) ?: return@withTransaction null
        val steps = dao.getSteps(step.objectiveId).map { it.toModel() }
        val index = steps.indexOfFirst { it.id == stepId }
        if (!Objectives.isOpen(steps, index, DateUtil.todayKey()) || step.isDone) return@withTransaction null
        val task = stepTask(step, objective.aspectId, week.id, DateUtil.now())
        db.taskDao().upsert(task)
        task.id
    }

    private fun stepTask(step: ObjectiveStep, aspectId: String?, weekId: String, now: String) = TaskEntity(
        id = UUID.randomUUID().toString(),
        weekId = weekId,
        title = step.title,
        aspectId = aspectId,
        dueDate = step.dueDate,
        status = TaskStatus.PENDING.value,
        resourceValue = ImportParser.computeResourceValue("medium", false, null, isManuallyAdded = true),
        createdAt = now,
        isManuallyAdded = true,
        source = TaskSource.PLANNED.name,
        // Its own slug, so it never collides with a same-titled task in the week's duplicate check.
        slug = "objective-step-${step.id}",
        objectiveStepId = step.id
    )

    /** Every task, in any week, that is work on one of this objective's steps. */
    fun observeStepTasks(stepIds: List<String>): Flow<List<Task>> =
        if (stepIds.isEmpty()) flowOf(emptyList())
        else db.taskDao().observeByObjectiveSteps(stepIds).map { list -> list.map { it.toModel() } }

    fun observeNotes(objectiveId: String): Flow<List<ObjectiveNote>> =
        dao.observeNotes(objectiveId).map { list -> list.map { it.toModel() } }

    suspend fun addNote(objectiveId: String, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        dao.upsertNote(ObjectiveNote(UUID.randomUUID().toString(), objectiveId, text, DateUtil.now()).toEntity())
    }

    suspend fun deleteNote(noteId: String) = dao.deleteNote(noteId)

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

    /** Delete an objective and its steps. Its steps' tasks stay, as ordinary tasks. */
    suspend fun delete(objectiveId: String) {
        db.withTransaction {
            val stepIds = dao.getSteps(objectiveId).map { it.id }
            if (stepIds.isNotEmpty()) db.taskDao().unlinkObjectiveSteps(stepIds)
            dao.delete(objectiveId)
        }
    }
}
