package com.lifeops.app.connection.service

import com.lifeops.app.data.repository.TaskRepository
import com.lifeops.app.data.repository.TimeEntryRepository

/** Use-case layer for logging time against a task. */
class TimeEntryService(
    private val timeEntryRepository: TimeEntryRepository,
    private val taskRepository: TaskRepository
) {

    /** Log [durationMinutes] against [taskId]. False if the task is unknown or duration <= 0. */
    suspend fun log(
        taskId: String,
        durationMinutes: Int,
        note: String? = null,
        subtaskId: String? = null
    ): Boolean {
        if (durationMinutes <= 0) return false
        taskRepository.getById(taskId) ?: return false
        timeEntryRepository.logTime(taskId, durationMinutes, note, subtaskId)
        return true
    }
}
