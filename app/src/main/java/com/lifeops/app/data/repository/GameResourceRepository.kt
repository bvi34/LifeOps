package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.GameResourceDao
import com.lifeops.app.data.db.dao.GameResourceMappingDao
import com.lifeops.app.data.model.GameResource
import com.lifeops.app.data.model.GameResourceMapping
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GameResourceRepository(
    private val gameResourceDao: GameResourceDao,
    private val gameResourceMappingDao: GameResourceMappingDao
) {
    fun observeResources(): Flow<List<GameResource>> =
        gameResourceDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeMappings(): Flow<List<GameResourceMapping>> =
        gameResourceMappingDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeMappingsForResource(gameResourceId: String): Flow<List<GameResourceMapping>> =
        gameResourceMappingDao.observeByGameResource(gameResourceId).map { list -> list.map { it.toModel() } }

    suspend fun upsertResource(resource: GameResource) = gameResourceDao.upsert(resource.toEntity())

    suspend fun upsertMapping(mapping: GameResourceMapping) = gameResourceMappingDao.upsert(mapping.toEntity())

    suspend fun deleteMapping(mapping: GameResourceMapping) =
        gameResourceMappingDao.deleteByResourceAndAspect(mapping.gameResourceId, mapping.aspectId)

    suspend fun getAll(): List<GameResource> = gameResourceDao.getAll().map { it.toModel() }

    suspend fun ensureDefaultSlots() {
        val existing = gameResourceDao.getAll()
        if (existing.isEmpty()) {
            val defaultNames = listOf("Gold", "Energy", "Wisdom", "Strength", "Spirit")
            defaultNames.forEachIndexed { index, name ->
                gameResourceDao.upsert(
                    com.lifeops.app.data.db.entities.GameResourceEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        slotIndex = index
                    )
                )
            }
        }
    }
}
