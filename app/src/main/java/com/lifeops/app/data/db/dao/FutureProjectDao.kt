package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lifeops.app.data.db.entities.FutureProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FutureProjectDao {
    @Query("SELECT * FROM future_projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FutureProjectEntity>>

    @Query("SELECT * FROM future_projects WHERE id = :id")
    fun observeById(id: String): Flow<FutureProjectEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: FutureProjectEntity)

    @Query("DELETE FROM future_projects WHERE id = :id")
    suspend fun delete(id: String)
}
