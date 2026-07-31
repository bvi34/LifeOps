package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.CounterCategoryTotal
import com.lifeops.app.data.db.dao.CounterDailyTotal
import com.lifeops.app.data.db.dao.CounterDao
import com.lifeops.app.data.db.dao.CounterIdTotal
import com.lifeops.app.data.db.dao.CounterWeeklyTotal
import com.lifeops.app.data.db.entities.CounterEntity
import com.lifeops.app.data.db.entities.CounterEventEntity
import com.lifeops.app.data.model.CounterEventWeather
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CounterRepositoryTest {

    /** Captures writes so we can assert what the repository persists; rollup rows are settable. */
    private class FakeCounterDao : CounterDao {
        val events = mutableListOf<CounterEventEntity>()
        val counters = mutableListOf<CounterEntity>()
        val updated = mutableListOf<CounterEntity>()
        var rollupRows: List<CounterCategoryTotal> = emptyList()

        override suspend fun insertCounter(counter: CounterEntity) { counters += counter }
        override suspend fun insertEvent(event: CounterEventEntity) { events += event }
        override suspend fun update(counter: CounterEntity) { updated += counter }
        override fun observeActive(): Flow<List<CounterEntity>> = flowOf(counters.filter { !it.isArchived })
        override suspend fun getAllSync(): List<CounterEntity> = counters
        override fun observeAll(): Flow<List<CounterEntity>> = flowOf(counters)
        override fun observeById(id: String): Flow<CounterEntity?> = flowOf(counters.firstOrNull { it.id == id })
        override suspend fun getById(id: String): CounterEntity? = counters.firstOrNull { it.id == id }
        override fun observeWeeklyTotal(counterId: String, weekKey: Int): Flow<Int> = flowOf(0)
        override fun observeCumulativeTotal(counterId: String): Flow<Int> = flowOf(0)
        override fun observeWeeklyTrend(counterId: String): Flow<List<CounterWeeklyTotal>> = flowOf(emptyList())
        override fun observeEvents(counterId: String): Flow<List<CounterEventEntity>> =
            flowOf(events.filter { it.counterId == counterId })
        override fun observeCategoryRollup(): Flow<List<CounterCategoryTotal>> = flowOf(rollupRows)
        override fun observeWeeklyTotalsByCounter(weekKey: Int): Flow<List<CounterIdTotal>> = flowOf(emptyList())
        override fun observeCumulativeTotalsByCounter(): Flow<List<CounterIdTotal>> = flowOf(emptyList())
        override fun observeDailyTotalsSince(startIso: String): Flow<List<CounterDailyTotal>> = flowOf(emptyList())
        override suspend fun sumByCounterSince(startIso: String): List<CounterIdTotal> = emptyList()
        override suspend fun getActiveCountersSync(): List<CounterEntity> = counters.filter { !it.isArchived }
    }

    /** Records reminder scheduling so tests can assert the repository drives the port correctly. */
    private class FakeReminderScheduler : HabitReminderScheduler {
        val scheduled = mutableListOf<Triple<String, String, Int>>()
        val cancelled = mutableListOf<String>()
        var cancelAllCount = 0
        override fun schedule(counterId: String, name: String, hour: Int) { scheduled += Triple(counterId, name, hour) }
        override fun cancel(counterId: String) { cancelled += counterId }
        override fun cancelAll() { cancelAllCount++ }
    }

    private val sampleWeather = CounterEventWeather(
        temperatureF = 72,
        feelsLikeF = 75,
        humidityPct = 40,
        windMph = 5,
        conditions = "Partly Cloudy",
        locationName = "Home",
        observedAt = "2026-07-26T12:00:00Z"
    )

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // --- Logging -------------------------------------------------------------------------------

    @Test
    fun `logEvent defaults to a single near-now tick whose weekKey matches its occurredAt`() = runTest {
        val dao = FakeCounterDao()
        val before = System.currentTimeMillis()
        CounterRepository(dao).logEvent("K1")

        val e = dao.events.single()
        assertEquals("K1", e.counterId)
        assertEquals(1, e.delta)
        assertNull(e.note)
        val occurredMillis = Instant.parse(e.occurredAt).toEpochMilli()
        assertTrue("occurredAt should be ~now", occurredMillis >= before)
        // The core invariant: weekKey is derived from the stored occurredAt.
        assertEquals(DateUtil.weekIndexFor(occurredMillis), e.weekKey)
        // With no weather provider wired, a tick carries no conditions.
        assertNull(e.weatherConditions)
        assertNull(e.weatherTempF)
    }

    @Test
    fun `logEvent backdates weekKey from occurredAt, not now`() = runTest {
        val dao = FakeCounterDao()
        // A Tuesday three weeks ago.
        val pastTuesday = DateUtil.currentWeekStart().minusWeeks(3).plusDays(1)
        val past = millis(pastTuesday)

        CounterRepository(dao).logEvent("K1", occurredAt = past)

        val e = dao.events.single()
        assertEquals(DateUtil.weekIndexFor(past), e.weekKey)
        assertEquals(Instant.ofEpochMilli(past).toString(), e.occurredAt)
        assertNotEquals(
            "backdated event must not carry the current week's key",
            DateUtil.weekIndexFor(System.currentTimeMillis()), e.weekKey
        )
    }

    @Test
    fun `logBulk records one event carrying the explicit delta`() = runTest {
        val dao = FakeCounterDao()
        val tuesday = DateUtil.currentWeekStart().plusDays(1)

        CounterRepository(dao).logBulk("K1", occurredAt = millis(tuesday), delta = 5)

        val e = dao.events.single() // one row, not five
        assertEquals(5, e.delta)
        assertEquals(DateUtil.weekIndexFor(millis(tuesday)), e.weekKey)
    }

    @Test
    fun `each logged event gets a distinct id`() = runTest {
        val dao = FakeCounterDao()
        val repo = CounterRepository(dao)
        repo.logEvent("K1")
        repo.logEvent("K1")
        assertEquals(2, dao.events.map { it.id }.toSet().size)
    }

    // --- Weather stamping ----------------------------------------------------------------------

    @Test
    fun `logEvent stamps the provider's conditions onto a live tick`() = runTest {
        val dao = FakeCounterDao()
        val repo = CounterRepository(dao, weatherProvider = { sampleWeather })

        repo.logEvent("K1")

        val e = dao.events.single()
        assertEquals(72, e.weatherTempF)
        assertEquals(75, e.weatherFeelsLikeF)
        assertEquals(40, e.weatherHumidityPct)
        assertEquals(5, e.weatherWindMph)
        assertEquals("Partly Cloudy", e.weatherConditions)
        assertEquals("Home", e.weatherLocationName)
        assertEquals("2026-07-26T12:00:00Z", e.weatherObservedAt)
    }

    @Test
    fun `a backdated tick records no weather even when conditions are available`() = runTest {
        val dao = FakeCounterDao()
        val repo = CounterRepository(dao, weatherProvider = { sampleWeather })
        val pastTuesday = DateUtil.currentWeekStart().minusWeeks(2).plusDays(1)

        repo.logEvent("K1", occurredAt = millis(pastTuesday))

        val e = dao.events.single()
        // Current conditions don't describe a past day, so the tick stays weather-free.
        assertNull(e.weatherConditions)
        assertNull(e.weatherTempF)
    }

    @Test
    fun `logEvent records no weather when the provider returns null`() = runTest {
        val dao = FakeCounterDao()
        val repo = CounterRepository(dao, weatherProvider = { null })

        repo.logEvent("K1")

        val e = dao.events.single()
        assertNull(e.weatherConditions)
        assertNull(e.weatherTempF)
    }

    // --- Counters ------------------------------------------------------------------------------

    @Test
    fun `createCounter persists the name and category attachment`() = runTest {
        val dao = FakeCounterDao()
        val counter = CounterRepository(dao).createCounter("K1", "Pushups", categoryId = "C1")

        assertEquals("Pushups", counter.name)
        assertEquals("C1", counter.categoryId)
        assertFalse(counter.isArchived)
        val persisted = dao.counters.single()
        assertEquals("C1", persisted.categoryId)
    }

    @Test
    fun `createCounter with no category is uncategorized`() = runTest {
        val dao = FakeCounterDao()
        val counter = CounterRepository(dao).createCounter("K1", "Standup")
        assertNull(counter.categoryId)
        assertNull(dao.counters.single().categoryId)
    }

    @Test
    fun `category rollup maps rows to a categoryId-keyed total map (null = uncategorized)`() = runTest {
        val dao = FakeCounterDao()
        dao.rollupRows = listOf(
            CounterCategoryTotal("C1", 4),
            CounterCategoryTotal("C2", 5),
            CounterCategoryTotal(null, 1)
        )
        val rollup = CounterRepository(dao).observeCategoryRollup().first()

        assertEquals(4, rollup["C1"])
        assertEquals(5, rollup["C2"])
        assertEquals(1, rollup[null]) // uncategorized bucket
    }

    // --- Habit reminders (via the injectable scheduler port) -----------------------------------

    @Test
    fun `creating a habit with a reminder schedules it through the scheduler`() = runTest {
        val dao = FakeCounterDao()
        val scheduler = FakeReminderScheduler()
        CounterRepository(dao, reminderScheduler = scheduler)
            .createCounter("K1", "Water", isHabit = true, reminderHour = 8)

        assertEquals(listOf(Triple("K1", "Water", 8)), scheduler.scheduled)
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `creating a non-habit counter cancels any reminder`() = runTest {
        val dao = FakeCounterDao()
        val scheduler = FakeReminderScheduler()
        CounterRepository(dao, reminderScheduler = scheduler)
            .createCounter("K1", "Coffees", isHabit = false, reminderHour = 8)

        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(listOf("K1"), scheduler.cancelled)
    }

    @Test
    fun `rescheduleAllReminders clears the queue then schedules each eligible habit`() = runTest {
        val dao = FakeCounterDao()
        val now = DateUtil.now()
        dao.counters += CounterEntity(id = "H1", name = "Water", createdAt = now, isHabit = true, reminderHour = 8)
        dao.counters += CounterEntity(id = "H2", name = "Stretch", createdAt = now, isHabit = true, reminderHour = 20)
        // Ineligible: habit but no hour, and a plain tally counter — neither should be scheduled.
        dao.counters += CounterEntity(id = "H3", name = "Read", createdAt = now, isHabit = true, reminderHour = null)
        dao.counters += CounterEntity(id = "T1", name = "Sodas", createdAt = now, isHabit = false, reminderHour = 9)
        // Archived habits stay silent even with an hour set.
        dao.counters += CounterEntity(id = "H4", name = "Walk", createdAt = now, isHabit = true, reminderHour = 7, isArchived = true)

        val scheduler = FakeReminderScheduler()
        CounterRepository(dao, reminderScheduler = scheduler).rescheduleAllReminders()

        assertEquals(1, scheduler.cancelAllCount)
        assertEquals(
            listOf(Triple("H1", "Water", 8), Triple("H2", "Stretch", 20)),
            scheduler.scheduled
        )
    }
}
