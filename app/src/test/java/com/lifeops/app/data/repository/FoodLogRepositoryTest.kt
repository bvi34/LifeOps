package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.FoodLogDao
import com.lifeops.app.data.db.entities.FoodItemEntity
import com.lifeops.app.data.db.entities.FoodLogEntryEntity
import com.lifeops.app.data.model.FoodLogSource
import com.lifeops.app.data.model.FoodSource
import com.lifeops.app.data.model.IngredientUnit
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FoodLogRepositoryTest {

    private class FakeFoodLogDao : FoodLogDao {
        val entries = mutableListOf<FoodLogEntryEntity>()
        var recentFoodItems: List<FoodItemEntity> = emptyList()
        var frequentFoodItems: List<FoodItemEntity> = emptyList()
        override suspend fun insert(entry: FoodLogEntryEntity) { entries += entry }
        override suspend fun update(entry: FoodLogEntryEntity) { entries.removeAll { it.id == entry.id }; entries += entry }
        override suspend fun getById(id: String): FoodLogEntryEntity? = entries.firstOrNull { it.id == id }
        override suspend fun getByWeeklyMenuItemId(weeklyMenuItemId: String): FoodLogEntryEntity? =
            entries.firstOrNull { it.weeklyMenuItemId == weeklyMenuItemId }
        override suspend fun delete(id: String) { entries.removeAll { it.id == id } }
        override suspend fun getRecent(limit: Int): List<FoodLogEntryEntity> = entries.takeLast(limit)
        override fun observeByDateRange(startIso: String, endIso: String): Flow<List<FoodLogEntryEntity>> =
            flowOf(entries.filter { it.loggedAt >= startIso && it.loggedAt < endIso })
        override suspend fun getRecentFoodItems(limit: Int): List<FoodItemEntity> = recentFoodItems.take(limit)
        override suspend fun getFrequentFoodItems(since: String, limit: Int): List<FoodItemEntity> = frequentFoodItems.take(limit)
    }

    private class FakeFoodItemDao : FoodItemDao {
        val items = mutableListOf<FoodItemEntity>()
        override suspend fun getById(id: String): FoodItemEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByFdcId(fdcId: Long): FoodItemEntity? = null
        override suspend fun search(query: String, limit: Int): List<FoodItemEntity> = emptyList()
        override suspend fun upsert(item: FoodItemEntity) { items.removeAll { it.id == item.id }; items += item }
        override suspend fun upsertAll(items: List<FoodItemEntity>) = items.forEach { upsert(it) }
        override suspend fun delete(id: String) { items.removeAll { it.id == id } }
        override suspend fun countBySource(source: String): Int = 0
    }

    private fun food(id: String) = FoodItemEntity(
        id, "Banana", null, 100.0, "g", 100.0, 89.0, 23.0, 1.1, 0.3, null, null,
        FoodSource.UsdaFoundation.name, 1002L, "2026-01-01T00:00:00Z"
    )

    @Test
    fun `logFoodItem snapshots macros from the food at log time`() = runTest {
        val logDao = FakeFoodLogDao()
        val foodDao = FakeFoodItemDao().apply { items += food("f1") }
        val entry = FoodLogRepository(logDao, foodDao).logFoodItem("f1", quantity = 2.0, unit = IngredientUnit.SERVING)
        assertEquals(178.0, entry!!.calories, 0.0001)
        assertEquals("f1", entry.foodItemId)
        assertEquals(1, logDao.entries.size)
    }

    @Test
    fun `logFoodItem returns null for an unknown food`() = runTest {
        val repo = FoodLogRepository(FakeFoodLogDao(), FakeFoodItemDao())
        assertNull(repo.logFoodItem("missing", 1.0, IngredientUnit.SERVING))
    }

    @Test
    fun `logAdHoc records a diary entry with no backing FoodItem`() = runTest {
        val logDao = FakeFoodLogDao()
        val entry = FoodLogRepository(logDao, FakeFoodItemDao())
            .logAdHoc("Restaurant Meal", 1.0, IngredientUnit.SERVING, 650.0, 60.0, 30.0, 25.0)
        assertNull(entry.foodItemId)
        assertEquals(650.0, logDao.entries.single().calories, 0.0001)
    }

    @Test
    fun `promoteToCustomFood turns an ad-hoc entry into a permanent Remembered food`() = runTest {
        val logDao = FakeFoodLogDao()
        val foodDao = FakeFoodItemDao()
        val repo = FoodLogRepository(logDao, foodDao)
        val entry = repo.logAdHoc("Restaurant Meal", 1.0, IngredientUnit.SERVING, 650.0, 60.0, 30.0, 25.0)

        val promoted = repo.promoteToCustomFood(entry.id)!!
        assertEquals(FoodSource.Remembered, promoted.source)
        assertEquals("Restaurant Meal", promoted.name)
        assertEquals(650.0, promoted.calories, 0.0001)
        assertEquals(foodDao.items.single().id, promoted.id)
    }

    @Test
    fun `promoteToCustomFood is a no-op when the entry already has a saved FoodItem`() = runTest {
        val logDao = FakeFoodLogDao()
        val foodDao = FakeFoodItemDao().apply { items += food("f1") }
        val repo = FoodLogRepository(logDao, foodDao)
        val entry = repo.logFoodItem("f1", 1.0, IngredientUnit.SERVING)!!

        val promoted = repo.promoteToCustomFood(entry.id)
        assertEquals("f1", promoted!!.id)
        assertEquals(1, foodDao.items.size) // no new row created
    }

    @Test
    fun `getRecentFoodItems and getFrequentFoodItems delegate to the dao`() = runTest {
        val logDao = FakeFoodLogDao().apply {
            recentFoodItems = listOf(food("f1"))
            frequentFoodItems = listOf(food("f2"))
        }
        val repo = FoodLogRepository(logDao, FakeFoodItemDao())
        assertEquals("f1", repo.getRecentFoodItems().single().id)
        assertEquals("f2", repo.getFrequentFoodItems().single().id)
    }

    @Test
    fun `observeForDate returns only entries logged on that calendar day`() = runTest {
        val logDao = FakeFoodLogDao()
        val repo = FoodLogRepository(logDao, FakeFoodItemDao())
        val onDay = repo.logAdHoc("Breakfast", 1.0, IngredientUnit.SERVING, 300.0, 30.0, 10.0, 10.0)
            .copy(loggedAt = DateUtil.isoFromEpoch(DateUtil.epochMillisForDate("2026-06-24", 8)))
        val otherDay = repo.logAdHoc("Dinner", 1.0, IngredientUnit.SERVING, 500.0, 40.0, 20.0, 15.0)
            .copy(loggedAt = DateUtil.isoFromEpoch(DateUtil.epochMillisForDate("2026-06-25", 19)))
        logDao.update(onDay.toEntity())
        logDao.update(otherDay.toEntity())

        val result = repo.observeForDate("2026-06-24").first()
        assertEquals(listOf("Breakfast"), result.map { it.name })
    }

    @Test
    fun `confirmEntry flips confirmed and stamps confirmedAt without touching macros`() = runTest {
        val logDao = FakeFoodLogDao()
        val repo = FoodLogRepository(logDao, FakeFoodItemDao())
        val entry = repo.logAdHoc("Planned Meal", 1.0, IngredientUnit.SERVING, 400.0, 40.0, 20.0, 10.0)
            .copy(confirmed = false, confirmedAt = null, source = FoodLogSource.PLANNED)
        logDao.update(entry.toEntity())

        val confirmed = repo.confirmEntry(entry.id)!!
        assertTrue(confirmed.confirmed)
        assertNotNull(confirmed.confirmedAt)
        assertEquals(400.0, confirmed.calories, 0.0001)
    }

    @Test
    fun `adjustEntry recomputes macros from the linked FoodItem when one exists`() = runTest {
        val logDao = FakeFoodLogDao()
        val foodDao = FakeFoodItemDao().apply { items += food("f1") } // 89 kcal/serving
        val repo = FoodLogRepository(logDao, foodDao)
        val entry = repo.logFoodItem("f1", 1.0, IngredientUnit.SERVING)!!

        val adjusted = repo.adjustEntry(entry.id, quantity = 2.0, unit = IngredientUnit.SERVING)!!
        assertEquals(178.0, adjusted.calories, 0.0001)
        assertEquals(FoodLogSource.ADJUSTED, adjusted.source)
        assertTrue(adjusted.confirmed)
    }

    @Test
    fun `adjustEntry scales macros proportionally when there is no linked FoodItem`() = runTest {
        val logDao = FakeFoodLogDao()
        val repo = FoodLogRepository(logDao, FakeFoodItemDao())
        val entry = repo.logAdHoc("Restaurant Meal", 1.0, IngredientUnit.SERVING, 600.0, 60.0, 30.0, 20.0)

        val adjusted = repo.adjustEntry(entry.id, quantity = 0.5, unit = IngredientUnit.SERVING)!!
        assertEquals(300.0, adjusted.calories, 0.0001)
        assertEquals(FoodLogSource.ADJUSTED, adjusted.source)
    }
}
