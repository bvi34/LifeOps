package com.lifeops.app.connection.service

import com.lifeops.app.data.model.FutureProject
import com.lifeops.app.data.model.FutureProjectStatus
import com.lifeops.app.data.repository.FutureProjectRepository

/** Use-case layer for the "someday" future-project backlog and its notes. */
class FutureProjectService(private val futureProjectRepository: FutureProjectRepository) {

    suspend fun create(title: String): FutureProject {
        require(title.isNotBlank()) { "Title must not be blank" }
        return futureProjectRepository.create(title.trim())
    }

    /** Rename a future project. The detail screen already holds the [project]. */
    suspend fun saveTitle(project: FutureProject, title: String) {
        require(title.isNotBlank()) { "Title must not be blank" }
        futureProjectRepository.saveTitle(project, title.trim())
    }

    /** Append a note (also bumps the project's recency). False if [content] is blank. */
    suspend fun addNote(projectId: String, content: String): Boolean {
        if (content.isBlank()) return false
        futureProjectRepository.addNote(projectId, content.trim())
        return true
    }

    suspend fun setStatus(id: String, status: FutureProjectStatus) =
        futureProjectRepository.setStatus(id, status)

    suspend fun delete(id: String) = futureProjectRepository.delete(id)
}
