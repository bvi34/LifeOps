package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.RunbookEntity
import com.lifeops.app.data.db.entities.RunbookStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RunbookDao {
    @Query("SELECT * FROM runbooks ORDER BY name")
    fun observeAll(): Flow<List<RunbookEntity>>

    @Query("SELECT * FROM runbooks ORDER BY name")
    suspend fun getAll(): List<RunbookEntity>

    @Query("SELECT * FROM runbooks WHERE id = :id")
    suspend fun getById(id: String): RunbookEntity?

    @Query("SELECT * FROM runbooks WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): RunbookEntity?

    @Query("SELECT * FROM runbook_steps WHERE runbookId = :runbookId ORDER BY stepOrder")
    suspend fun getSteps(runbookId: String): List<RunbookStepEntity>

    @Query("SELECT * FROM runbook_steps WHERE runbookId IN (:runbookIds) ORDER BY runbookId, stepOrder")
    suspend fun getStepsForRunbooks(runbookIds: List<String>): List<RunbookStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunbook(runbook: RunbookEntity)

    @Update
    suspend fun updateRunbook(runbook: RunbookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStep(step: RunbookStepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<RunbookStepEntity>)

    @Query("DELETE FROM runbooks WHERE id = :id")
    suspend fun deleteRunbook(id: String)

    @Query("DELETE FROM runbook_steps WHERE runbookId = :runbookId")
    suspend fun deleteStepsForRunbook(runbookId: String)
}
