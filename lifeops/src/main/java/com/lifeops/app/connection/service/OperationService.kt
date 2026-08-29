package com.lifeops.app.connection.service

import com.lifeops.app.data.model.Operation
import com.lifeops.app.data.model.OperationStatus
import com.lifeops.app.data.repository.OperationRepository
import java.util.UUID

/**
 * Use-case layer for operations. Thin today — operations have little cross-repository policy — but it
 * gives the connection routes a stable surface and keeps id generation and status transitions in
 * one place. Repositories remain the source of truth.
 */
class OperationService(private val operationRepository: OperationRepository) {

    suspend fun create(
        title: String,
        aspectId: String? = null,
        categoryId: String? = null,
        description: String? = null,
        id: String = UUID.randomUUID().toString(),
        sourceFutureOperationId: String? = null
    ): Operation {
        require(title.isNotBlank()) { "Operation title must not be blank" }
        val operation = operationRepository.createOperation(
            id = id,
            title = title.trim(),
            aspectId = aspectId,
            categoryId = categoryId,
            sourceFutureOperationId = sourceFutureOperationId
        )
        // createOperation doesn't take a description; apply it in a follow-up update when supplied.
        return description?.takeIf { it.isNotBlank() }?.let {
            val withDesc = operation.copy(description = it)
            operationRepository.update(withDesc)
            withDesc
        } ?: operation
    }

    /** Partial update; each non-null field replaces the current value. Null if [id] is unknown. */
    suspend fun update(
        id: String,
        title: String? = null,
        aspectId: String? = null,
        categoryId: String? = null,
        description: String? = null
    ): Operation? {
        val current = operationRepository.getOperationById(id) ?: return null
        val updated = current.copy(
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: current.title,
            aspectId = aspectId ?: current.aspectId,
            categoryId = categoryId ?: current.categoryId,
            description = description ?: current.description
        )
        operationRepository.update(updated)
        return updated
    }

    /** Set the operation's status (stamps completedAt on COMPLETED). False if [id] is unknown. */
    suspend fun setStatus(id: String, status: OperationStatus): Boolean {
        operationRepository.getOperationById(id) ?: return false
        operationRepository.setOperationStatus(id, status)
        return true
    }

    suspend fun complete(id: String): Boolean = setStatus(id, OperationStatus.COMPLETED)

    suspend fun reopen(id: String): Boolean = setStatus(id, OperationStatus.ACTIVE)
}
