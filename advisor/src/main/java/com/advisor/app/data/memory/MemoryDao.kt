package com.advisor.app.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // --- writes ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<MemoryTagEntity>)

    @Query("DELETE FROM advisor_memory_tags WHERE memoryId = :memoryId")
    suspend fun clearTags(memoryId: String)

    @Query("DELETE FROM advisor_memories WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("UPDATE advisor_memories SET lastRecalledAt = :ts, recallCount = recallCount + 1 WHERE id = :id")
    suspend fun markRecalled(id: String, ts: Long)

    @Query("UPDATE advisor_memories SET pinned = :pinned, updatedAt = :ts WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, ts: Long)

    // --- reads ---

    @Transaction
    @Query("SELECT * FROM advisor_memories WHERE archived = 0 ORDER BY pinned DESC, salience DESC, updatedAt DESC")
    fun observeMemories(): Flow<List<MemoryWithTags>>

    @Transaction
    @Query("SELECT * FROM advisor_memories WHERE archived = 0")
    suspend fun getAll(): List<MemoryWithTags>

    @Transaction
    @Query(
        "SELECT * FROM advisor_memories WHERE archived = 0 AND id IN " +
            "(SELECT memoryId FROM advisor_memory_tags WHERE tag IN (:tags)) " +
            "ORDER BY pinned DESC, salience DESC, updatedAt DESC"
    )
    fun observeByTags(tags: List<String>): Flow<List<MemoryWithTags>>

    @Query("SELECT tag AS tag, COUNT(*) AS count FROM advisor_memory_tags GROUP BY tag ORDER BY count DESC, tag ASC")
    fun observeTagCounts(): Flow<List<TagCount>>
}
