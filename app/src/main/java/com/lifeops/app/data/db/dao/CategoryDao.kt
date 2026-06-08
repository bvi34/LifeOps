package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE aspectId = :aspectId AND isArchived = 0 ORDER BY name")
    fun observeByAspect(aspectId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE aspectId = :aspectId AND LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByAspectAndName(aspectId: String, name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE categories SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("SELECT * FROM categories ORDER BY name")
    suspend fun getAllSync(): List<CategoryEntity>
}
