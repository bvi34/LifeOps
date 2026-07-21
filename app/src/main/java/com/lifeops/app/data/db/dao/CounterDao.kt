package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.CounterEntity
import com.lifeops.app.data.db.entities.CounterEventEntity
import kotlinx.coroutines.flow.Flow

/** One row of a per-counter weekly trend: the total logged in a given week. */
data class CounterWeeklyTotal(val weekKey: Int, val total: Int)

/** Category rollup row: cumulative total for all counters sharing a category (null = uncategorized). */
data class CounterCategoryTotal(val categoryId: String?, val total: Int)

/** Per-counter total, used to populate the counters list in one query instead of N. */
data class CounterIdTotal(val counterId: String, val total: Int)

@Dao
interface CounterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCounter(counter: CounterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CounterEventEntity)

    // Edits (rename, attach/detach category) go through @Update, not insert-REPLACE, so an
    // edit can never cascade-delete the counter's events the way a PK-conflict REPLACE would.
    @Update
    suspend fun update(counter: CounterEntity)

    @Query("SELECT * FROM counters WHERE isArchived = 0 ORDER BY sortOrder ASC, createdAt ASC")
    fun observeActive(): Flow<List<CounterEntity>>

    /** All counters (own + archived) for global search. */
    @Query("SELECT * FROM counters")
    suspend fun getAllSync(): List<CounterEntity>

    @Query("SELECT * FROM counters ORDER BY isArchived ASC, sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<CounterEntity>>

    @Query("SELECT * FROM counters WHERE id = :id")
    fun observeById(id: String): Flow<CounterEntity?>

    @Query("SELECT * FROM counters WHERE id = :id")
    suspend fun getById(id: String): CounterEntity?

    // 1. Weekly window — total logged for one counter in a single week.
    @Query("SELECT COALESCE(SUM(delta), 0) FROM counter_events WHERE counterId = :counterId AND weekKey = :weekKey")
    fun observeWeeklyTotal(counterId: String, weekKey: Int): Flow<Int>

    // 2. Cumulative — all-time total for one counter.
    @Query("SELECT COALESCE(SUM(delta), 0) FROM counter_events WHERE counterId = :counterId")
    fun observeCumulativeTotal(counterId: String): Flow<Int>

    // 3. Per-counter trend by weekKey — one total per week, oldest first.
    @Query("SELECT weekKey AS weekKey, COALESCE(SUM(delta), 0) AS total FROM counter_events WHERE counterId = :counterId GROUP BY weekKey ORDER BY weekKey ASC")
    fun observeWeeklyTrend(counterId: String): Flow<List<CounterWeeklyTotal>>

    // 4. Timestamped detail — every event for one counter, newest first (cumulative-with-time).
    @Query("SELECT * FROM counter_events WHERE counterId = :counterId ORDER BY occurredAt DESC")
    fun observeEvents(counterId: String): Flow<List<CounterEventEntity>>

    // 5. Category rollup — cumulative total grouped by the counter's category.
    @Query("SELECT c.categoryId AS categoryId, COALESCE(SUM(e.delta), 0) AS total FROM counter_events e INNER JOIN counters c ON c.id = e.counterId GROUP BY c.categoryId")
    fun observeCategoryRollup(): Flow<List<CounterCategoryTotal>>

    // List support: every counter's total for one week, and all-time, in a single query each.
    @Query("SELECT counterId AS counterId, COALESCE(SUM(delta), 0) AS total FROM counter_events WHERE weekKey = :weekKey GROUP BY counterId")
    fun observeWeeklyTotalsByCounter(weekKey: Int): Flow<List<CounterIdTotal>>

    @Query("SELECT counterId AS counterId, COALESCE(SUM(delta), 0) AS total FROM counter_events GROUP BY counterId")
    fun observeCumulativeTotalsByCounter(): Flow<List<CounterIdTotal>>

    // Reports: per-counter totals summed over events on/after a cutoff instant.
    @Query("SELECT counterId AS counterId, COALESCE(SUM(delta), 0) AS total FROM counter_events WHERE occurredAt >= :startIso GROUP BY counterId")
    suspend fun sumByCounterSince(startIso: String): List<CounterIdTotal>

    @Query("SELECT * FROM counters WHERE isArchived = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getActiveCountersSync(): List<CounterEntity>
}
