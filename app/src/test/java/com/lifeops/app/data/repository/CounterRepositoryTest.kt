package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.CounterCategoryTotal
import com.lifeops.app.data.db.dao.CounterDao
import com.lifeops.app.data.db.dao.CounterWeeklyTotal
import com.lifeops.app.data.db.entities.CounterEntity
import com.lifeops.app.data.db.entities.CounterEventEntity
import com.lifeops.app.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
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
        override fun observeWeeklyTotal(counterId: String, weekKey: Int): Flow<Int> = throw NotImplementedError()
        override fun observeCumulativeTotal(counterId: String): Flow<Int> = throw NotImplementedError()
        override fun observeWeeklyTrend(counterId: String): Flow<List<CounterWeeklyTotal>> = throw NotImplementedError()
        override fun observeEvents(counterId: String): Flow<List<CounterEventEntity>> = throw NotImplementedError()
        override fun observeCategoryRollup(): Flow<List<CounterCategoryTotal>> = flowOf(rollupRows)
    }

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

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
}
