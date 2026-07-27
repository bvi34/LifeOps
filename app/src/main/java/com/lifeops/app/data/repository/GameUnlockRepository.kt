package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.GameUnlockDao
import com.lifeops.app.data.db.entities.GameUnlockEntity
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Permanently-unlocked store items (DESIGN.md §9). A run reads the set once at start to seed its
 * pools; a store purchase writes one row. Write-mostly-once; nothing else mutates it.
 */
class GameUnlockRepository(private val gameUnlockDao: GameUnlockDao) {

    fun observeUnlockedIds(): Flow<Set<String>> =
        gameUnlockDao.observeAll().map { list -> list.map { it.id }.toSet() }

    suspend fun unlockedIds(): Set<String> = gameUnlockDao.getAllIds().toSet()

    suspend fun unlock(itemId: String) =
        gameUnlockDao.insert(GameUnlockEntity(id = itemId, unlockedAt = DateUtil.now()))

    suspend fun clear() = gameUnlockDao.clear()
}
