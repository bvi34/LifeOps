package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE status = 'active' ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY status ASC, createdAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE aspectId = :aspectId AND status = 'active' ORDER BY createdAt DESC")
    suspend fun getActiveByAspect(aspectId: String): List<ProjectEntity>

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET status = :status, completedAt = :completedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, completedAt: String?)

    @Query("SELECT * FROM projects")
    suspend fun getAll(): List<ProjectEntity>
}
