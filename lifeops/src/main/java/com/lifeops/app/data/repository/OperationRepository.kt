package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.OperationDao
import com.lifeops.app.data.db.entities.OperationEntity
import com.lifeops.app.data.model.Operation
import com.lifeops.app.data.model.OperationStatus
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OperationRepository(private val operationDao: OperationDao) {
    fun observeActive(): Flow<List<Operation>> =
        operationDao.observeActive().map { list -> list.map { it.toModel() } }

    fun observeAll(): Flow<List<Operation>> =
        operationDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun createOperation(
        id: String,
        title: String,
        aspectId: String?,
        categoryId: String? = null,
        sourceFutureOperationId: String? = null
    ): Operation {
        val entity = OperationEntity(
            id = id, title = title, aspectId = aspectId, categoryId = categoryId,
            createdAt = DateUtil.now(), sourceFutureOperationId = sourceFutureOperationId
        )
        operationDao.upsert(entity)
        return entity.toModel()
    }

    suspend fun upsert(operation: Operation) = operationDao.upsert(operation.toEntity())

    suspend fun update(operation: Operation) = operationDao.update(operation.toEntity())

    suspend fun setStatus(id: String, status: OperationStatus) {
        val completedAt = if (status == OperationStatus.COMPLETED) DateUtil.now() else null
        operationDao.updateStatus(id, status.value, completedAt)
    }

    suspend fun getAll(): List<Operation> = operationDao.getAll().map { it.toModel() }

    suspend fun getOperationById(id: String): Operation? = operationDao.getById(id)?.toModel()

    suspend fun setOperationStatus(id: String, status: OperationStatus) {
        val completedAt = if (status == OperationStatus.COMPLETED) DateUtil.now() else null
        operationDao.updateStatus(id, status.value, completedAt)
    }

    private fun OperationEntity.toModel() = Operation(id, title, aspectId, categoryId, OperationStatus.from(status), description, createdAt, completedAt, sourceFutureOperationId)
    private fun Operation.toEntity() = OperationEntity(id, title, aspectId, categoryId, status.value, description, createdAt, completedAt, sourceFutureOperationId)
}
