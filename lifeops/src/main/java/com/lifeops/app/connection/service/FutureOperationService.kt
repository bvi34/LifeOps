package com.lifeops.app.connection.service

import com.lifeops.app.data.model.FutureOperation
import com.lifeops.app.data.model.FutureOperationStatus
import com.lifeops.app.data.repository.FutureOperationRepository

/** Use-case layer for the "someday" future-operation backlog and its notes. */
class FutureOperationService(private val futureOperationRepository: FutureOperationRepository) {

    suspend fun create(title: String): FutureOperation {
        require(title.isNotBlank()) { "Title must not be blank" }
        return futureOperationRepository.create(title.trim())
    }

    /** Rename a future operation. The detail screen already holds the [operation]. */
    suspend fun saveTitle(operation: FutureOperation, title: String) {
        require(title.isNotBlank()) { "Title must not be blank" }
        futureOperationRepository.saveTitle(operation, title.trim())
    }

    /** Append a note (also bumps the operation's recency). False if [content] is blank. */
    suspend fun addNote(operationId: String, content: String): Boolean {
        if (content.isBlank()) return false
        futureOperationRepository.addNote(operationId, content.trim())
        return true
    }

    suspend fun setStatus(id: String, status: FutureOperationStatus) =
        futureOperationRepository.setStatus(id, status)

    suspend fun delete(id: String) = futureOperationRepository.delete(id)
}
