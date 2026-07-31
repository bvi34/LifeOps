package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.BusyBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BusyBlockDao {
    /** The user's own schedule (personId IS NULL) — drives the Planning calendar. */
    @Query("SELECT * FROM busy_blocks WHERE personId IS NULL ORDER BY startMinutes ASC")
    fun observeMine(): Flow<List<BusyBlockEntity>>

    /** A single person's schedule — drives the Person detail schedule section. */
    @Query("SELECT * FROM busy_blocks WHERE personId = :personId ORDER BY startMinutes ASC")
    fun observeForPerson(personId: String): Flow<List<BusyBlockEntity>>

    /** All blocks (own + everyone's) — the best-time engine partitions these by personId. */
    @Query("SELECT * FROM busy_blocks")
    fun observeAll(): Flow<List<BusyBlockEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(block: BusyBlockEntity)

    @Query("DELETE FROM busy_blocks WHERE id = :id")
    suspend fun delete(id: String)

    /** Full-table snapshot for backup export. */
    @Query("SELECT * FROM busy_blocks")
    suspend fun getAll(): List<BusyBlockEntity>
}
