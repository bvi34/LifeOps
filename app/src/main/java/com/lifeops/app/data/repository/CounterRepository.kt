package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.CounterDao
import com.lifeops.app.data.db.entities.CounterEntity
import com.lifeops.app.data.db.entities.CounterEventEntity
import com.lifeops.app.data.model.Counter
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class CounterRepository(private val counterDao: CounterDao) {

    // --- Counter <-> category attach (same shape as ProjectRepository) ---

    /** Create a counter, optionally attached to a category. Mirrors createProject. */
    suspend fun createCounter(id: String, name: String, categoryId: String? = null): Counter {
        val entity = CounterEntity(id = id, name = name, categoryId = categoryId, createdAt = DateUtil.now())
        counterDao.insertCounter(entity)
        return entity.toModel()
    }

    /**
     * Persist an edited counter. Attach/detach is just update(counter.copy(categoryId = ...)),
     * exactly how projects do it — categoryId is nullable, and a null detaches. The category
     * itself is resolved the same way projects/imports do (AspectRepository.findOrCreateCategory),
     * so counters and projects share one set of categories.
     */
    suspend fun update(counter: Counter) = counterDao.update(counter.toEntity())

    /** Wired category rollup: cumulative tick total per category (null key = uncategorized). */
    fun observeCategoryRollup(): Flow<Map<String?, Int>> =
        counterDao.observeCategoryRollup().map { rows -> rows.associate { it.categoryId to it.total } }

    private fun CounterEntity.toModel() = Counter(id, name, categoryId, isArchived, sortOrder, createdAt)
    private fun Counter.toEntity() = CounterEntity(id, name, categoryId, isArchived, sortOrder, createdAt)

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
