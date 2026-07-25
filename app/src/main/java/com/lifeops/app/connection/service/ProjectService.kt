package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Project
import com.lifeops.app.data.model.ProjectStatus
import com.lifeops.app.data.repository.ProjectRepository
import java.util.UUID

/**
 * Use-case layer for projects. Thin today — projects have little cross-repository policy — but it
 * gives the connection routes a stable surface and keeps id generation and status transitions in
 * one place. Repositories remain the source of truth.
 */
class ProjectService(private val projectRepository: ProjectRepository) {

    suspend fun create(
        title: String,
        aspectId: String? = null,
        categoryId: String? = null,
        description: String? = null,
        id: String = UUID.randomUUID().toString(),
        sourceFutureProjectId: String? = null
    ): Project {
        require(title.isNotBlank()) { "Project title must not be blank" }
        val project = projectRepository.createProject(
            id = id,
            title = title.trim(),
            aspectId = aspectId,
            categoryId = categoryId,
            sourceFutureProjectId = sourceFutureProjectId
        )
        // createProject doesn't take a description; apply it in a follow-up update when supplied.
        return description?.takeIf { it.isNotBlank() }?.let {
            val withDesc = project.copy(description = it)
            projectRepository.update(withDesc)
            withDesc
        } ?: project
    }

    /** Partial update; each non-null field replaces the current value. Null if [id] is unknown. */
    suspend fun update(
        id: String,
        title: String? = null,
        aspectId: String? = null,
        categoryId: String? = null,
        description: String? = null
    ): Project? {
        val current = projectRepository.getProjectById(id) ?: return null
        val updated = current.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: current.title,
            aspectId = aspectId ?: current.aspectId,
            categoryId = categoryId ?: current.categoryId,
            description = description ?: current.description
        )
        projectRepository.update(updated)
        return updated
    }

    /** Set the project's status (stamps completedAt on COMPLETED). False if [id] is unknown. */
    suspend fun setStatus(id: String, status: ProjectStatus): Boolean {
        projectRepository.getProjectById(id) ?: return false
        projectRepository.setProjectStatus(id, status)
        return true
    }

    suspend fun complete(id: String): Boolean = setStatus(id, ProjectStatus.COMPLETED)

    suspend fun reopen(id: String): Boolean = setStatus(id, ProjectStatus.ACTIVE)
}
