package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.PhoneActivityEventDao
import com.lifeops.app.data.model.PhoneActivityEvent
import com.lifeops.app.data.model.PhoneActivityType
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.SleepInferenceService
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Stores the raw phone-activity stream (screen on/off, charging) captured by
 * [com.lifeops.app.receiver.PhoneActivityReceiver], and turns the previous night's slice of it into a
 * [SleepInferenceService.SleepReconstruction] for the morning prompt. The receiver calls [record] on
 * every event; [reconstructLastNight] reads back the overnight window and infers the sleep.
 *
 * Events older than [RETENTION_DAYS] are pruned on write — the derived sleep report lives in
 * wellness_checkins, so the raw stream only needs to survive long enough to reconstruct one night.
 */
class PhoneActivityRepository(
    private val dao: PhoneActivityEventDao
) {
    /** Persist one event immediately (called from the receiver, off the main thread). */
    suspend fun record(type: PhoneActivityType, at: Long = System.currentTimeMillis()) {
        dao.insert(
            PhoneActivityEvent(
                id = UUID.randomUUID().toString(),
                type = type,
                occurredAt = at,
                dayKey = DateUtil.localDateKey(at)
            ).toEntity()
        )
        dao.pruneBefore(at - RETENTION_DAYS * DAY_MS)
    }

    /** Raw events inside the given epoch-millis window, oldest first. */
    suspend fun eventsBetween(startMillis: Long, endMillis: Long): List<PhoneActivityEvent> =
        dao.eventsBetween(startMillis, endMillis).map { it.toModel() }

    suspend fun eventCount(): Int = dao.count()

    /**
     * Reconstruct the sleep for the night ending at [now] (a morning). The window opens at the
     * previous evening (18:00 local) so a normal bedtime and any late-evening use are both in view,
     * and closes at [now]. Returns null when there aren't enough captured events to be confident.
     */
    suspend fun reconstructLastNight(
        now: Long = System.currentTimeMillis()
    ): SleepInferenceService.SleepReconstruction? {
        val windowStart = eveningStartMillis(now)
        val events = eventsBetween(windowStart, now)
        return SleepInferenceService.reconstruct(events, windowStart, now)
    }

    companion object {
        private const val RETENTION_DAYS = 7L
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val EVENING_HOUR = 18

        /** 18:00 local of the evening preceding the morning at [now] (today if it's already past 6pm). */
        internal fun eveningStartMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            val nowLocal = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
            val eveningDate = if (nowLocal.hour >= EVENING_HOUR) nowLocal.toLocalDate()
            else nowLocal.toLocalDate().minusDays(1)
            return eveningDate.atTime(EVENING_HOUR, 0)
                .atZone(zone).toInstant().toEpochMilli()
        }
    }
}
