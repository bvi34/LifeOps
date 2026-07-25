package com.lifeops.app.connection.service

import com.lifeops.app.data.model.RunbookWithSteps
import com.lifeops.app.data.repository.RunbookRepository

/** Use-case layer for runbooks (reusable step checklists) and the subtasks they stamp onto tasks. */
class RunbookService(private val runbookRepository: RunbookRepository) {

    suspend fun create(name: String, steps: List<String>): RunbookWithSteps {
        require(name.isNotBlank()) { "Runbook name must not be blank" }
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotBlank() }
        require(cleanSteps.isNotEmpty()) { "A runbook needs at least one step" }
        return runbookRepository.createRunbook(name.trim(), cleanSteps)
    }

    suspend fun delete(runbookId: String) = runbookRepository.deleteRunbook(runbookId)

    /** Stamp a runbook's steps as subtasks onto a task. False if [runbookId] is unknown. */
    suspend fun stamp(taskId: String, runbookId: String): Boolean {
        val runbook = runbookRepository.getRunbookWithSteps(runbookId) ?: return false
        runbookRepository.stampRunbook(taskId, runbook)
        return true
    }

    suspend fun setSubtaskChecked(subtaskId: String, checked: Boolean) =
        runbookRepository.setSubtaskChecked(subtaskId, checked)

    suspend fun deleteSubtask(subtaskId: String) = runbookRepository.deleteSubtask(subtaskId)
}
