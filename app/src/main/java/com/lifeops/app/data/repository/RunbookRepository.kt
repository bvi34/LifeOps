package com.lifeops.app.data.repository

import androidx.room.withTransaction
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.*
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toModel
import com.lifeops.app.util.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RunbookRepository(private val db: LifeOpsDatabase) {

    fun observeRunbooks(): Flow<List<Runbook>> =
        db.runbookDao().observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getAllRunbooks(): List<Runbook> =
        db.runbookDao().getAll().map { it.toModel() }

    suspend fun findByName(name: String): RunbookWithSteps? {
        val runbook = db.runbookDao().findByName(name)?.toModel() ?: return null
        val steps = db.runbookDao().getSteps(runbook.id).map { it.toModel() }
        return RunbookWithSteps(runbook, steps)
    }

    suspend fun getRunbookWithSteps(runbookId: String): RunbookWithSteps? {
        val runbook = db.runbookDao().getById(runbookId)?.toModel() ?: return null
        val steps = db.runbookDao().getSteps(runbookId).map { it.toModel() }
        return RunbookWithSteps(runbook, steps)
    }

    suspend fun getAllRunbooksWithSteps(): List<RunbookWithSteps> {
        val runbooks = db.runbookDao().getAll()
        if (runbooks.isEmpty()) return emptyList()
        val stepsByRunbook = db.runbookDao()
            .getStepsForRunbooks(runbooks.map { it.id })
            .groupBy { it.runbookId }
        return runbooks.map { rb ->
            RunbookWithSteps(
                runbook = rb.toModel(),
                steps = (stepsByRunbook[rb.id] ?: emptyList()).map { it.toModel() }
            )
        }
    }

    suspend fun createRunbook(name: String, steps: List<String>): RunbookWithSteps {
        val runbook = Runbook(UUID.randomUUID().toString(), name, DateUtil.now())
        val stepModels = steps.mapIndexed { i, label ->
            RunbookStep(UUID.randomUUID().toString(), runbook.id, label, i)
        }
        db.withTransaction {
            db.runbookDao().upsertRunbook(runbook.toEntity())
            db.runbookDao().upsertSteps(stepModels.map { it.toEntity() })
        }
        return RunbookWithSteps(runbook, stepModels)
    }

    suspend fun updateRunbook(runbook: Runbook, steps: List<String>) {
        val stepModels = steps.mapIndexed { i, label ->
            RunbookStep(UUID.randomUUID().toString(), runbook.id, label, i)
        }
        db.withTransaction {
            db.runbookDao().updateRunbook(runbook.toEntity())
            db.runbookDao().deleteStepsForRunbook(runbook.id)
            db.runbookDao().upsertSteps(stepModels.map { it.toEntity() })
        }
    }

    suspend fun deleteRunbook(runbookId: String) =
        db.runbookDao().deleteRunbook(runbookId)

    /**
     * Stamp engine: inserts fresh subtask rows for every step in [runbook] under [taskId].
     * Always additive — calling twice produces two sets of subtasks. UI should gate re-stamps
     * if idempotency is desired.
     */
    suspend fun stampRunbook(taskId: String, runbook: RunbookWithSteps) {
        val offset = db.subtaskDao().countByTask(taskId)
        val subtasks = runbook.steps.mapIndexed { i, step ->
            Subtask(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                runbookId = runbook.runbook.id,
                label = step.label,
                stepOrder = offset + i,
                isChecked = false
            )
        }
        db.subtaskDao().insertAll(subtasks.map { it.toEntity() })
    }

    suspend fun stampRunbookById(taskId: String, runbookId: String) {
        val rb = getRunbookWithSteps(runbookId) ?: return
        stampRunbook(taskId, rb)
    }

    fun observeSubtasks(taskId: String): Flow<List<Subtask>> =
        db.subtaskDao().observeByTask(taskId).map { list -> list.map { it.toModel() } }

    suspend fun getSubtasks(taskId: String): List<Subtask> =
        db.subtaskDao().getByTask(taskId).map { it.toModel() }

    suspend fun setSubtaskChecked(subtaskId: String, checked: Boolean) =
        db.subtaskDao().setChecked(subtaskId, checked)

    suspend fun getSubtaskCounts(taskId: String): Pair<Int, Int> {
        val total = db.subtaskDao().countByTask(taskId)
        val checked = db.subtaskDao().countCheckedByTask(taskId)
        return total to checked
    }
}
