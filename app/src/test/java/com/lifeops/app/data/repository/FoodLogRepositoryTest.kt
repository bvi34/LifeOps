package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.FoodLogDao
import com.lifeops.app.data.db.entities.FoodItemEntity
import com.lifeops.app.data.db.entities.FoodLogEntryEntity
import com.lifeops.app.data.model.FoodSource
import com.lifeops.app.data.model.IngredientUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FoodLogRepositoryTest {

    private class FakeFoodLogDao : FoodLogDao {
        val entries = mutableListOf<FoodLogEntryEntity>()
        var recentFoodItems: List<FoodItemEntity> = emptyList()
        var frequentFoodItems: List<FoodItemEntity> = emptyList()
        override suspend fun insert(entry: FoodLogEntryEntity) { entries += entry }
        override suspend fun getById(id: String): FoodLogEntryEntity? = entries.firstOrNull { it.id == id }
        override suspend fun getRecent(limit: Int): List<FoodLogEntryEntity> = entries.takeLast(limit)
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
}
