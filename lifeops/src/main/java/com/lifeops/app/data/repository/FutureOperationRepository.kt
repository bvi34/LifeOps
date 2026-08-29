package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FutureOperationDao
import com.lifeops.app.data.model.FutureOperation
import com.lifeops.app.data.model.FutureOperationNote
import com.lifeops.app.data.model.FutureOperationStatus
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** A operation plus a preview of its most recent note, for the list screen. */
data class FutureOperationListItem(val operation: FutureOperation, val latestNote: String?)

class FutureOperationRepository(private val futureOperationDao: FutureOperationDao) {
    fun observeAll(): Flow<List<FutureOperationListItem>> =
        futureOperationDao.observeAllWithPreview().map { list ->
            list.map { FutureOperationListItem(it.operation.toModel(), it.latestNote) }
        }

    fun observeById(id: String): Flow<FutureOperation?> = futureOperationDao.observeById(id).map { it?.toModel() }

    fun observeNotes(operationId: String): Flow<List<FutureOperationNote>> =
        futureOperationDao.observeNotes(operationId).map { list -> list.map { it.toModel() } }

    suspend fun getNotes(operationId: String): List<FutureOperationNote> =
        futureOperationDao.getNotes(operationId).map { it.toModel() }

    suspend fun create(title: String): FutureOperation {
        val now = DateUtil.now()
        val operation = FutureOperation(UUID.randomUUID().toString(), title, now, now)
        futureOperationDao.upsert(operation.toEntity())
        return operation
    }

    suspend fun saveTitle(operation: FutureOperation, title: String) {
        futureOperationDao.upsert(operation.copy(title = title, updatedAt = DateUtil.now()).toEntity())
    }

    suspend fun addNote(operationId: String, content: String) {
        val now = DateUtil.now()
        futureOperationDao.insertNote(
            FutureOperationNote(UUID.randomUUID().toString(), operationId, content, now).toEntity()
        )
        // Keep the list's recency ordering in step with note activity, not just title edits.
        futureOperationDao.touch(operationId, now)
    }

    suspend fun setStatus(id: String, status: FutureOperationStatus) =
        futureOperationDao.updateStatus(id, status.value)

    suspend fun delete(id: String) = futureOperationDao.delete(id)
}
