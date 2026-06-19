package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.CounterDao
import com.lifeops.app.data.db.entities.CounterEventEntity
import com.lifeops.app.util.DateUtil
import java.util.UUID

class CounterRepository(private val counterDao: CounterDao) {

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
