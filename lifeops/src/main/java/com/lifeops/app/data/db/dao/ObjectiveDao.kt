package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lifeops.app.data.db.entities.ObjectiveEntity
import com.lifeops.app.data.db.entities.ObjectiveNoteEntity
import com.lifeops.app.data.db.entities.ObjectiveStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ObjectiveDao {
    @Query("SELECT * FROM objectives ORDER BY dueDate ASC, createdAt ASC")
    fun observeAll(): Flow<List<ObjectiveEntity>>

    @Query("SELECT * FROM objective_steps ORDER BY objectiveId, position ASC")
    fun observeAllSteps(): Flow<List<ObjectiveStepEntity>>

    @Query("SELECT * FROM objectives")
    suspend fun getAll(): List<ObjectiveEntity>

    @Query("SELECT * FROM objectives WHERE id = :id")
    suspend fun getById(id: String): ObjectiveEntity?

    @Query("SELECT * FROM objective_steps")
    suspend fun getAllSteps(): List<ObjectiveStepEntity>

    @Query("SELECT * FROM objective_steps WHERE objectiveId = :objectiveId ORDER BY position ASC")
    suspend fun getSteps(objectiveId: String): List<ObjectiveStepEntity>

    // @Upsert, never @Insert(REPLACE), for the parent: REPLACE deletes the old row first, which
    // cascades and wipes the objective's steps.
    @Upsert
    suspend fun upsert(objective: ObjectiveEntity)

    @Upsert
    suspend fun upsertStep(step: ObjectiveStepEntity)

    @Query("DELETE FROM objective_steps WHERE objectiveId = :objectiveId AND id NOT IN (:keepIds)")
    suspend fun deleteStepsExcept(objectiveId: String, keepIds: List<String>)

    @Query("UPDATE objective_steps SET completedAt = :completedAt WHERE id = :stepId")
    suspend fun setStepCompletedAt(stepId: String, completedAt: String?)

    @Query("UPDATE objectives SET status = :status, closedAt = :closedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: String, status: String, closedAt: String?, updatedAt: String)

    @Query("DELETE FROM objectives WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM objective_notes WHERE objectiveId = :objectiveId ORDER BY createdAt ASC")
    fun observeNotes(objectiveId: String): Flow<List<ObjectiveNoteEntity>>

    @Query("SELECT * FROM objective_notes")
    suspend fun getAllNotes(): List<ObjectiveNoteEntity>

    @Upsert
    suspend fun upsertNote(note: ObjectiveNoteEntity)

    @Query("DELETE FROM objective_notes WHERE id = :id")
    suspend fun deleteNote(id: String)
}
