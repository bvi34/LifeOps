package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.CounterEntity
import com.lifeops.app.data.db.entities.CounterEventEntity
import kotlinx.coroutines.flow.Flow

/** One row of a per-counter weekly trend: the total logged in a given week. */
data class CounterWeeklyTotal(val weekKey: Int, val total: Int)

/** Category rollup row: cumulative total for all counters sharing a category (null = uncategorized). */
data class CounterCategoryTotal(val categoryId: String?, val total: Int)

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
}
