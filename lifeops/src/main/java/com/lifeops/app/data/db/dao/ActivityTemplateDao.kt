package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.ActivityOverrideEntity
import com.lifeops.app.data.db.entities.ActivityTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityTemplateDao {

    // @Upsert (not @Insert REPLACE) so edits update in place rather than churning the row.
    @Upsert
    suspend fun upsert(template: ActivityTemplateEntity)

    @Delete
    suspend fun delete(template: ActivityTemplateEntity)

    @Query("SELECT * FROM activity_templates ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<ActivityTemplateEntity>>

    @Query("SELECT * FROM activity_templates WHERE id = :id")
    suspend fun getById(id: String): ActivityTemplateEntity?

    @Query("SELECT * FROM activity_templates")
    suspend fun getAll(): List<ActivityTemplateEntity>

    @Query("SELECT COUNT(*) FROM activity_templates")
    suspend fun count(): Int

    // --- Manual overrides (learning signal) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverride(override: ActivityOverrideEntity)

    @Query("SELECT * FROM activity_overrides ORDER BY createdAt ASC")
    fun observeOverrides(): Flow<List<ActivityOverrideEntity>>

    @Query("SELECT * FROM activity_overrides")
    suspend fun getAllOverrides(): List<ActivityOverrideEntity>

    @Query("DELETE FROM activity_overrides WHERE activityId = :activityId AND field = :field")
    suspend fun clearOverrides(activityId: String, field: String)
}
