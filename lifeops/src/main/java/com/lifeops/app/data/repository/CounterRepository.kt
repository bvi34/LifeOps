package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.CounterDao
import com.lifeops.app.data.db.dao.CounterDailyTotal
import com.lifeops.app.data.db.dao.CounterWeeklyTotal
import com.lifeops.app.data.db.entities.CounterEntity
import com.lifeops.app.data.db.entities.CounterEventEntity
import com.lifeops.app.data.model.Counter
import com.lifeops.app.data.model.CounterEvent
import com.lifeops.app.data.model.CounterEventWeather
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Supplies the conditions to stamp onto a live counter tick. Kept as a tiny functional seam (rather
 * than a direct WeatherRepository dependency) so the counter layer stays weather-source-agnostic and
 * testable, and so a null provider simply means "don't capture weather". Returns null whenever no
 * usable reading is available — the repository then records the tick with no weather.
 */
fun interface CounterWeatherProvider {
    suspend fun currentConditions(): CounterEventWeather?
}

class CounterRepository(
    private val counterDao: CounterDao,
    private val reminderScheduler: HabitReminderScheduler = NoopHabitReminderScheduler,
    private val weatherProvider: CounterWeatherProvider? = null
) {

    // --- Counters list / lifecycle (same shape as OperationRepository) ---

    fun observeAll(): Flow<List<Counter>> =
        counterDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeActive(): Flow<List<Counter>> =
        counterDao.observeActive().map { list -> list.map { it.toModel() } }

    fun observeCounter(id: String): Flow<Counter?> =
        counterDao.observeById(id).map { it?.toModel() }

    suspend fun getById(id: String): Counter? = counterDao.getById(id)?.toModel()

    /** Create a counter, optionally a habit with its own daily reminder. Mirrors createOperation. */
    suspend fun createCounter(
        id: String,
        name: String,
        categoryId: String? = null,
        isHabit: Boolean = false,
        reminderHour: Int? = null
    ): Counter {
        val entity = CounterEntity(
            id = id,
            name = name,
            categoryId = categoryId,
            createdAt = DateUtil.now(),
            isHabit = isHabit,
            reminderHour = reminderHour
        )
        counterDao.insertCounter(entity)
        val model = entity.toModel()
        syncReminder(model)
        return model
    }

    /**
     * Persist an edited counter (rename, archive, attach/detach category, habit flag, reminder).
     * Attach is just update(counter.copy(categoryId = ...)), exactly how operations do it —
     * categoryId is nullable and null detaches. Categories are the same shared set operations/imports
     * use (AspectRepository.findOrCreateCategory), so counters and operations can't fork categories.
     * The habit reminder is re-synced from the saved state so a changed hour / cleared reminder /
     * un-flagged habit takes effect immediately.
     */
    suspend fun update(counter: Counter) {
        counterDao.update(counter.toEntity())
        syncReminder(counter)
    }

    suspend fun setArchived(counter: Counter, archived: Boolean) {
        val updated = counter.copy(isArchived = archived)
        counterDao.update(updated.toEntity())
        // Archiving silences the reminder without discarding the chosen hour; unarchiving restores it.
        syncReminder(updated)
    }

    // --- Habit reminders ---

    /** Queue or cancel [counter]'s daily reminder to match its current habit/reminder/archive state. */
    private fun syncReminder(counter: Counter) {
        val hour = counter.reminderHour
        if (counter.isHabit && !counter.isArchived && hour != null) {
            reminderScheduler.schedule(counter.id, counter.name, hour)
        } else {
            reminderScheduler.cancel(counter.id)
        }
    }

    /**
     * Reconcile all habit reminders against the database — called at app start, since WorkManager's
     * queue can be lost across reinstall/device-transfer while the reminder settings live in Room.
     * Clears the queue and re-schedules one reminder per eligible habit.
     */
    suspend fun rescheduleAllReminders() {
        reminderScheduler.cancelAll()
        counterDao.getAllSync()
            .map { it.toModel() }
            .filter { it.isHabit && !it.isArchived && it.reminderHour != null }
            .forEach { reminderScheduler.schedule(it.id, it.name, it.reminderHour!!) }
    }

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

    /** Daily totals for dashboard widgets and streak calculations. */
    fun observeDailyTotalsSince(startIso: String): Flow<List<CounterDailyTotal>> =
        counterDao.observeDailyTotalsSince(startIso)

    // --- Reports ---

    /** Per-counter totals summed over events on/after [startIso] (a report-range cutoff). */
    suspend fun sumByCounterSince(startIso: String): Map<String, Int> =
        counterDao.sumByCounterSince(startIso).associate { it.counterId to it.total }

    /** Active (non-archived) counters, for labelling report rows. */
    suspend fun getActiveCountersSync(): List<Counter> =
        counterDao.getActiveCountersSync().map { it.toModel() }

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
        // Only a live tick gets weather — a backdated/bulk entry ([occurredAt] far from now) has no
        // matching current conditions, and stamping "now" onto a past day would be a lie. The
        // provider is also allowed to return null (no location, empty/stale cache), in which case
        // the event simply carries no weather.
        val weather = if (isLiveTick(occurredAt)) weatherProvider?.currentConditions() else null
        counterDao.insertEvent(buildEvent(counterId, occurredAt, delta, note, weather))
    }

    /** True when [occurredAt] is close enough to now that current conditions describe it. */
    private fun isLiveTick(occurredAt: Long): Boolean =
        kotlin.math.abs(System.currentTimeMillis() - occurredAt) <= LIVE_TICK_WINDOW_MS

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

    private fun buildEvent(
        counterId: String,
        occurredAt: Long,
        delta: Int,
        note: String?,
        weather: CounterEventWeather?
    ) =
        CounterEventEntity(
            id = UUID.randomUUID().toString(),
            counterId = counterId,
            weekKey = DateUtil.weekIndexFor(occurredAt),
            occurredAt = DateUtil.isoFromEpoch(occurredAt),
            delta = delta,
            note = note,
            weatherTempF = weather?.temperatureF,
            weatherFeelsLikeF = weather?.feelsLikeF,
            weatherHumidityPct = weather?.humidityPct,
            weatherWindMph = weather?.windMph,
            weatherConditions = weather?.conditions,
            weatherLocationName = weather?.locationName,
            weatherObservedAt = weather?.observedAt
        )

    companion object {
        /**
         * How near "now" an event's [occurredAt] must be to count as a live tick worth stamping with
         * current weather. Ten minutes tolerates entry lag and clock skew while still excluding
         * deliberately backdated or bulk-backfilled entries.
         */
        private const val LIVE_TICK_WINDOW_MS = 10 * 60 * 1000L
    }
}
