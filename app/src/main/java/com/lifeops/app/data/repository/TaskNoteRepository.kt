package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.TaskNoteDao
import com.lifeops.app.data.db.entities.TaskNoteEntity
import com.lifeops.app.data.model.TaskNote
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class TaskNoteRepository(private val taskNoteDao: TaskNoteDao) {
    fun observeByWeek(weekId: String): Flow<List<TaskNote>> =
        taskNoteDao.observeByWeek(weekId).map { list -> list.map { it.toModel() } }

    suspend fun addNote(taskId: String, content: String) {
        taskNoteDao.insert(
            TaskNoteEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                content = content,
                createdAt = DateUtil.now()
            )
        )
    }

    suspend fun deleteNote(id: String) = taskNoteDao.delete(id)

    suspend fun getByTask(taskId: String): List<TaskNote> =
        taskNoteDao.getByTask(taskId).map { it.toModel() }

    suspend fun getByTaskIds(taskIds: List<String>): List<TaskNote> =
        taskNoteDao.getByTaskIds(taskIds).map { it.toModel() }
}
