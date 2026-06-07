package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.AspectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AspectDao {
    @Query("SELECT * FROM aspects WHERE isArchived = 0 ORDER BY name")
    fun observeActive(): Flow<List<AspectEntity>>

    @Query("SELECT * FROM aspects ORDER BY name")
    fun observeAll(): Flow<List<AspectEntity>>

    @Query("SELECT * FROM aspects WHERE id = :id")
    suspend fun getById(id: String): AspectEntity?

    @Query("SELECT * FROM aspects WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): AspectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(aspect: AspectEntity)

    @Update
    suspend fun update(aspect: AspectEntity)

    @Query("UPDATE aspects SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)
}
