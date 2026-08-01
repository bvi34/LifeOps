package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.MilestoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MilestoneDao {
    // @Upsert (not @Insert REPLACE) so an edit never deletes-then-reinserts the row.
    @Upsert
    suspend fun upsert(milestone: MilestoneEntity)

    @Delete
    suspend fun delete(milestone: MilestoneEntity)

    /** All milestones, most recent accomplishment first. */
    @Query("SELECT * FROM milestones ORDER BY achievedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestones WHERE aspectId = :aspectId ORDER BY achievedAt DESC, createdAt DESC")
    fun observeForAspect(aspectId: String): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestones WHERE personId = :personId ORDER BY achievedAt DESC, createdAt DESC")
    fun observeForPerson(personId: String): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM milestones")
    suspend fun getAll(): List<MilestoneEntity>
}
