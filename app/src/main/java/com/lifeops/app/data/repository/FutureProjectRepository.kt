package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FutureProjectDao
import com.lifeops.app.data.model.FutureProject
import com.lifeops.app.data.model.FutureProjectNote
import com.lifeops.app.data.model.FutureProjectStatus
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** A project plus a preview of its most recent note, for the list screen. */
data class FutureProjectListItem(val project: FutureProject, val latestNote: String?)

class FutureProjectRepository(private val futureProjectDao: FutureProjectDao) {
    fun observeAll(): Flow<List<FutureProjectListItem>> =
        futureProjectDao.observeAllWithPreview().map { list ->
            list.map { FutureProjectListItem(it.project.toModel(), it.latestNote) }
        }

    fun observeById(id: String): Flow<FutureProject?> = futureProjectDao.observeById(id).map { it?.toModel() }

    fun observeNotes(projectId: String): Flow<List<FutureProjectNote>> =
        futureProjectDao.observeNotes(projectId).map { list -> list.map { it.toModel() } }

    suspend fun create(title: String): FutureProject {
        val now = DateUtil.now()
        val project = FutureProject(UUID.randomUUID().toString(), title, now, now)
        futureProjectDao.upsert(project.toEntity())
        return project
    }

    suspend fun saveTitle(project: FutureProject, title: String) {
        futureProjectDao.upsert(project.copy(title = title, updatedAt = DateUtil.now()).toEntity())
    }

    suspend fun addNote(projectId: String, content: String) {
        val now = DateUtil.now()
        futureProjectDao.insertNote(
            FutureProjectNote(UUID.randomUUID().toString(), projectId, content, now).toEntity()
        )
        // Keep the list's recency ordering in step with note activity, not just title edits.
        futureProjectDao.touch(projectId, now)
    }

    suspend fun setStatus(id: String, status: FutureProjectStatus) =
        futureProjectDao.updateStatus(id, status.value)

    suspend fun delete(id: String) = futureProjectDao.delete(id)
}
