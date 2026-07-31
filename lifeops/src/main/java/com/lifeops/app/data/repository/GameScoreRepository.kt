package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.GameScoreDao
import com.lifeops.app.data.model.GameScore
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The game scoreboard: a historical log of finished runs (DESIGN.md §6). Write-once per run. */
class GameScoreRepository(private val gameScoreDao: GameScoreDao) {

    fun observeScores(): Flow<List<GameScore>> =
        gameScoreDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun record(score: GameScore) = gameScoreDao.insert(score.toEntity())

    suspend fun bestScore(): Long = gameScoreDao.bestScore() ?: 0L

    suspend fun clear() = gameScoreDao.clear()
}
