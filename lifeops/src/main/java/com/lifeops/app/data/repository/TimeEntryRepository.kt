package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.TimeEntryDao
import com.lifeops.app.data.db.entities.TimeEntryEntity
import com.lifeops.app.data.model.TimeEntry
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class TimeEntryRepository(private val timeEntryDao: TimeEntryDao) {
    fun observeByWeek(weekId: String): Flow<List<TimeEntry>> =
        timeEntryDao.observeByWeek(weekId).map { list -> list.map { it.toModel() } }

    suspend fun getAllSince(since: String): List<TimeEntry> =
        timeEntryDao.getAllSince(since).map { it.toModel() }

    suspend fun getByTask(taskId: String): List<TimeEntry> =
        timeEntryDao.getByTask(taskId).map { it.toModel() }

    fun observeByTask(taskId: String): Flow<List<TimeEntry>> =
        timeEntryDao.observeByTask(taskId).map { list -> list.map { it.toModel() } }

    suspend fun logTime(taskId: String, durationMinutes: Int, note: String? = null, subtaskId: String? = null) {
        timeEntryDao.insert(
            TimeEntryEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                durationMinutes = durationMinutes,
                note = note,
                recordedAt = DateUtil.now(),
                subtaskId = subtaskId
            )
        )
    }
}
