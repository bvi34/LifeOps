package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.CounterDao
import com.lifeops.app.data.db.dao.CounterWeeklyTotal
import com.lifeops.app.data.db.entities.CounterEntity
import com.lifeops.app.data.db.entities.CounterEventEntity
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.CounterEvent
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class CounterRepository(private val counterDao: CounterDao) {

    // --- Counters list / lifecycle (same shape as ProjectRepository) ---

    fun observeAll(): Flow<List<Counter>> =
        counterDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeActive(): Flow<List<Counter>> =
        counterDao.observeActive().map { list -> list.map { it.toModel() } }

    fun observeCounter(id: String): Flow<Counter?> =
        counterDao.observeById(id).map { it?.toModel() }

    suspend fun getById(id: String): Counter? = counterDao.getById(id)?.toModel()

    /** Create a counter, optionally attached to a category. Mirrors createProject. */
    suspend fun createCounter(id: String, name: String, categoryId: String? = null): Counter {
        val entity = CounterEntity(id = id, name = name, categoryId = categoryId, createdAt = DateUtil.now())
        counterDao.insertCounter(entity)
        return entity.toModel()
    }

    /**
     * Persist an edited counter (rename, archive, attach/detach category). Attach is just
     * update(counter.copy(categoryId = ...)), exactly how projects do it — categoryId is
     * nullable and null detaches. Categories are the same shared set projects/imports use
     * (AspectRepository.findOrCreateCategory), so counters and projects can't fork categories.
     */
    suspend fun update(counter: Counter) = counterDao.update(counter.toEntity())

    suspend fun setArchived(counter: Counter, archived: Boolean) =
        counterDao.update(counter.copy(isArchived = archived).toEntity())

    // --- Reporting (the five DAO queries, surfaced as models) ---

    /** Weekly window: total logged for [counterId] in [weekKey]. */
    fun observeWeeklyTotal(counterId: String, weekKey: Int): Flow<Int> =
        counterDao.observeWeeklyTotal(counterId, weekKey)

    /** Cumulative: all-time total for [counterId]. */
    fun observeCumulativeTotal(counterId: String): Flow<Int> =
        counterDao.observeCumulativeTotal(counterId)

    /** Per-week trend for [counterId], oldest week first. */
    fun observeWeeklyTrend(counterId: String): Flow<List<CounterWeeklyTotal>> =
        counterDao.observeWeeklyTrend(counterId)

    /** Timestamped detail: every event for [counterId], newest first. */
    fun observeEvents(counterId: String): Flow<List<CounterEvent>> =
        counterDao.observeEvents(counterId).map { list -> list.map { it.toModel() } }

    /** Category rollup: cumulative total per category (null key = uncategorized). */
    fun observeCategoryRollup(): Flow<Map<String?, Int>> =
        counterDao.observeCategoryRollup().map { rows -> rows.associate { it.categoryId to it.total } }

    /** counterId -> total for [weekKey], for the counters list. */
    fun observeWeeklyTotalsByCounter(weekKey: Int): Flow<Map<String, Int>> =
        counterDao.observeWeeklyTotalsByCounter(weekKey).map { rows -> rows.associate { it.counterId to it.total } }

    /** counterId -> all-time total, for the counters list. */
    fun observeCumulativeTotalsByCounter(): Flow<Map<String, Int>> =
        counterDao.observeCumulativeTotalsByCounter().map { rows -> rows.associate { it.counterId to it.total } }

    // --- Logging ---

    /**
     * Log a tick on [counterId]. [occurredAt] (epoch millis) defaults to now but may be any
     * past instant to backdate. weekKey is stamped from [occurredAt] via the shared
     * DateUtil.weekIndexFor — never from "now" — so a backdated event lands in the week it
     * actually happened. [occurredAt] is also what gets stored (as ISO), so the timestamped
     * detail view shows the real time, not the entry time.
     */
    suspend fun logEvent(
        counterId: String,
        occurredAt: Long = System.currentTimeMillis(),
        delta: Int = 1,
        note: String? = null
    ) {
        counterDao.insertEvent(buildEvent(counterId, occurredAt, delta, note))
    }

    /**
     * Backfill convenience: logEvent with an explicit [delta] — "five times Tuesday" is
     * logBulk(id, tuesdayMillis, 5). It records one event of weight [delta] (every reporting
     * query SUMs delta), not [delta] separate rows. [occurredAt] is required because a bulk
     * entry is always "this many, on this day".
     */
    suspend fun logBulk(
        counterId: String,
        occurredAt: Long,
        delta: Int,
        note: String? = null
    ) = logEvent(counterId, occurredAt, delta, note)

    private fun buildEvent(counterId: String, occurredAt: Long, delta: Int, note: String?) =
        CounterEventEntity(
            id = UUID.randomUUID().toString(),
            counterId = counterId,
            weekKey = DateUtil.weekIndexFor(occurredAt),
            occurredAt = DateUtil.isoFromEpoch(occurredAt),
            delta = delta,
            note = note
        )
}
