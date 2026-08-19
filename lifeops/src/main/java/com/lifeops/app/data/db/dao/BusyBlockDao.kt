package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.BusyBlockEntity
import com.lifeops.app.data.db.entities.BusyBlockPersonEntity
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

    @Query("SELECT * FROM busy_blocks WHERE id = :id")
    suspend fun getById(id: String): BusyBlockEntity?

    /**
     * The block a previously-pulled Google Calendar instance maps to, if any. Keyed by
     * (event, calendar, occurrence date) rather than just the event id — a recurring Google event
     * expands into one instance per occurrence, each pulled in as its own one-off block.
     */
    @Query(
        "SELECT * FROM busy_blocks WHERE googleEventId = :googleEventId AND googleCalendarId = :googleCalendarId " +
        "AND specificDate = :specificDate LIMIT 1"
    )
    suspend fun findByGoogleInstance(googleEventId: Long, googleCalendarId: Long, specificDate: String): BusyBlockEntity?

    /** Full-table snapshot for backup export. */
    @Query("SELECT * FROM busy_blocks")
    suspend fun getAll(): List<BusyBlockEntity>

    // --- People tagged on a busy block (join) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun attachPerson(link: BusyBlockPersonEntity)

    @Query("DELETE FROM busy_block_people WHERE busyBlockId = :blockId")
    suspend fun clearPeople(blockId: String)

    /** Person ids tagged on [blockId] — for showing "with …" on a busy block. */
    @Query("SELECT personId FROM busy_block_people WHERE busyBlockId = :blockId")
    suspend fun getPersonIdsForBlock(blockId: String): List<String>

    @Query("SELECT * FROM busy_block_people")
    fun observeAllPeopleLinks(): Flow<List<BusyBlockPersonEntity>>

    /** Full-table snapshot for backup export. */
    @Query("SELECT * FROM busy_block_people")
    suspend fun getAllPeopleLinks(): List<BusyBlockPersonEntity>
}
