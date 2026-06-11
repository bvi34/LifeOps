package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.ProjectDao
import com.lifeops.app.data.db.entities.ProjectEntity
import com.lifeops.app.data.model.Project
import com.lifeops.app.data.model.ProjectStatus
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProjectRepository(private val projectDao: ProjectDao) {
    fun observeActive(): Flow<List<Project>> =
        projectDao.observeActive().map { list -> list.map { it.toModel() } }

    fun observeAll(): Flow<List<Project>> =
        projectDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun createProject(id: String, title: String, aspectId: String?, categoryId: String? = null): Project {
        val entity = ProjectEntity(
            id = id, title = title, aspectId = aspectId, categoryId = categoryId, createdAt = DateUtil.now()
        )
        projectDao.upsert(entity)
        return entity.toModel()
    }

    suspend fun upsert(project: Project) = projectDao.upsert(project.toEntity())

    suspend fun setStatus(id: String, status: ProjectStatus) {
        val completedAt = if (status == ProjectStatus.COMPLETED) DateUtil.now() else null
        projectDao.updateStatus(id, status.value, completedAt)
    }

    suspend fun getAll(): List<Project> = projectDao.getAll().map { it.toModel() }

    private fun ProjectEntity.toModel() = Project(id, title, aspectId, categoryId, ProjectStatus.from(status), description, createdAt, completedAt)
    private fun Project.toEntity() = ProjectEntity(id, title, aspectId, categoryId, status.value, description, createdAt, completedAt)
}
