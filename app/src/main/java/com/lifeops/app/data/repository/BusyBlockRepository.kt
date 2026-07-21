package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.BusyBlockDao
import com.lifeops.app.data.model.BusyBlock
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BusyBlockRepository(private val dao: BusyBlockDao) {
    /** The user's own schedule (personId == null). */
    fun observeMine(): Flow<List<BusyBlock>> =
        dao.observeMine().map { list -> list.map { it.toModel() } }

    /** One person's schedule. */
    fun observeForPerson(personId: String): Flow<List<BusyBlock>> =
        dao.observeForPerson(personId).map { list -> list.map { it.toModel() } }

    /** Everything, own + everyone's — the best-time engine partitions by personId. */
    fun observeAll(): Flow<List<BusyBlock>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun upsert(block: BusyBlock) = dao.upsert(block.toEntity())

    suspend fun delete(id: String) = dao.delete(id)
}
