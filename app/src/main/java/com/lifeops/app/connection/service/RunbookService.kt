package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Runbook
import com.lifeops.app.data.model.RunbookWithSteps
import com.lifeops.app.data.repository.RunbookRepository

/** Use-case layer for runbooks (reusable step checklists) and the subtasks they stamp onto tasks. */
class RunbookService(private val runbookRepository: RunbookRepository) {

    suspend fun create(name: String, steps: List<String>): RunbookWithSteps {
        require(name.isNotBlank()) { "Runbook name must not be blank" }
        // Blank steps are dropped; an empty list is allowed (the repository permits a step-less
        // runbook). Callers that require at least one step gate on that themselves.
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotBlank() }
        return runbookRepository.createRunbook(name.trim(), cleanSteps)
    }

    /** Replace a runbook's name and steps. The settings editor holds the [runbook]. */
    suspend fun update(runbook: Runbook, steps: List<String>) {
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotBlank() }
        runbookRepository.updateRunbook(runbook, cleanSteps)
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
