package com.lifeops.app.data.repository

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.FoodLogDao
import com.lifeops.app.data.db.dao.RecipeDao
import com.lifeops.app.data.db.dao.WeeklyMenuItemDao
import com.lifeops.app.data.db.entities.FoodItemEntity
import com.lifeops.app.data.db.entities.FoodLogEntryEntity
import com.lifeops.app.data.db.entities.RecipeEntity
import com.lifeops.app.data.db.entities.RecipeIngredientEntity
import com.lifeops.app.data.db.entities.WeeklyMenuItemEntity
import com.lifeops.app.data.model.FoodLogSource
import com.lifeops.app.data.model.FoodSource
import com.lifeops.app.data.model.IngredientUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WeeklyMenuItemRepositoryTest {

    private class FakeWeeklyMenuItemDao : WeeklyMenuItemDao {
        val items = mutableListOf<WeeklyMenuItemEntity>()
        override fun observeByWeek(weekStartDate: String): Flow<List<WeeklyMenuItemEntity>> =
            flowOf(items.filter { it.weekStartDate == weekStartDate })
        override suspend fun getById(id: String): WeeklyMenuItemEntity? = items.firstOrNull { it.id == id }
        override suspend fun getByAssignedDate(date: String): List<WeeklyMenuItemEntity> =
            items.filter { it.assignedDate == date }
        override suspend fun upsert(item: WeeklyMenuItemEntity) { items.removeAll { it.id == item.id }; items += item }
        override suspend fun delete(id: String) { items.removeAll { it.id == id } }
    }

    private class FakeFoodLogDao : FoodLogDao {
        val entries = mutableListOf<FoodLogEntryEntity>()
        override suspend fun insert(entry: FoodLogEntryEntity) { entries += entry }
        override suspend fun update(entry: FoodLogEntryEntity) { entries.removeAll { it.id == entry.id }; entries += entry }
        override suspend fun getById(id: String): FoodLogEntryEntity? = entries.firstOrNull { it.id == id }
        override suspend fun getByWeeklyMenuItemId(weeklyMenuItemId: String): FoodLogEntryEntity? =
            entries.firstOrNull { it.weeklyMenuItemId == weeklyMenuItemId }
        override suspend fun delete(id: String) { entries.removeAll { it.id == id } }
        override suspend fun getRecent(limit: Int): List<FoodLogEntryEntity> = entries.takeLast(limit)
        override fun observeByDateRange(startIso: String, endIso: String): Flow<List<FoodLogEntryEntity>> =
            flowOf(entries.filter { it.loggedAt >= startIso && it.loggedAt < endIso })
        override suspend fun getRecentFoodItems(limit: Int): List<FoodItemEntity> = emptyList()
        override suspend fun getFrequentFoodItems(since: String, limit: Int): List<FoodItemEntity> = emptyList()
    }

    private class FakeRecipeDao : RecipeDao {
        val recipes = mutableListOf<RecipeEntity>()
        val ingredients = mutableListOf<RecipeIngredientEntity>()
        override fun observeAll(): Flow<List<RecipeEntity>> = flowOf(recipes)
        override suspend fun getById(id: String): RecipeEntity? = recipes.firstOrNull { it.id == id }
        override suspend fun upsert(recipe: RecipeEntity) { recipes.removeAll { it.id == recipe.id }; recipes += recipe }
        override suspend fun delete(id: String) { recipes.removeAll { it.id == id } }
        override suspend fun getIngredients(recipeId: String): List<RecipeIngredientEntity> =
            ingredients.filter { it.recipeId == recipeId }.sortedBy { it.sortOrder }
        override fun observeIngredients(recipeId: String): Flow<List<RecipeIngredientEntity>> =
            flowOf(ingredients.filter { it.recipeId == recipeId })
        override suspend fun upsertIngredient(ingredient: RecipeIngredientEntity) {
            ingredients.removeAll { it.id == ingredient.id }; ingredients += ingredient
        }
        override suspend fun deleteIngredient(id: String) { ingredients.removeAll { it.id == id } }
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
        override suspend fun count(): Int = items.size
    }

    private fun food(id: String) = FoodItemEntity(
        id, "Chicken", null, 100.0, "g", 100.0, 200.0, 0.0, 30.0, 8.0, null, null,
        FoodSource.UsdaFoundation.name, null, "2026-01-01T00:00:00Z"
    )

    private fun buildRepo(): Triple<WeeklyMenuItemRepository, FakeWeeklyMenuItemDao, FakeFoodLogDao> {
        val menuDao = FakeWeeklyMenuItemDao()
        val logDao = FakeFoodLogDao()
        val recipeDao = FakeRecipeDao()
        val foodItemDao = FakeFoodItemDao()
        val recipeRepository = RecipeRepository(recipeDao, foodItemDao)
        return Triple(WeeklyMenuItemRepository(menuDao, logDao, recipeRepository), menuDao, logDao)
    }

    @Test
    fun `createMenuItem stores a floating meal with no day assigned`() = runTest {
        val (repo, _, _) = buildRepo()
        val item = repo.createMenuItem("2026-06-22", "Meatloaf")
        assertNull(item.assignedDate)
        assertNull(item.recipeId)
    }

    @Test
    fun `assignToDate spawns an unconfirmed Planned log entry linked to the menu item`() = runTest {
        val (repo, _, logDao) = buildRepo()
        val item = repo.createMenuItem("2026-06-22", "Meatloaf")

        val updated = repo.assignToDate(item.id, "2026-06-24")!!
        assertEquals("2026-06-24", updated.assignedDate)

        val entry = logDao.entries.single()
        assertEquals(FoodLogSource.PLANNED, FoodLogSource.from(entry.source))
        assertFalse(entry.confirmed)
        assertEquals(item.id, entry.weeklyMenuItemId)
    }

    @Test
    fun `assignToDate computes calories from the linked recipe`() = runTest {
        val menuDao = FakeWeeklyMenuItemDao()
        val logDao = FakeFoodLogDao()
        val recipeDao = FakeRecipeDao()
        val foodItemDao = FakeFoodItemDao().apply { items += food("f1") }
        val recipeRepository = RecipeRepository(recipeDao, foodItemDao)
        val recipe = recipeRepository.createRecipe("Roast Chicken", servings = 2.0)
        recipeRepository.addIngredient(recipe.id, "f1", quantity = 2.0, unit = IngredientUnit.SERVING) // 400 kcal total
        val repo = WeeklyMenuItemRepository(menuDao, logDao, recipeRepository)

        val item = repo.createMenuItem("2026-06-22", "Roast Chicken", recipeId = recipe.id, plannedServings = 1.0)
        repo.assignToDate(item.id, "2026-06-24")

        assertEquals(200.0, logDao.entries.single().calories, 0.0001) // 400 / 2 servings * 1 planned serving
    }

    @Test
    fun `re-assigning to a new date moves the existing unconfirmed entry instead of duplicating it`() = runTest {
        val (repo, _, logDao) = buildRepo()
        val item = repo.createMenuItem("2026-06-22", "Meatloaf")

        repo.assignToDate(item.id, "2026-06-24")
        repo.assignToDate(item.id, "2026-06-25")

        assertEquals(1, logDao.entries.size)
    }

    @Test
    fun `re-assigning never moves an entry the user already confirmed`() = runTest {
        val (repo, _, logDao) = buildRepo()
        val item = repo.createMenuItem("2026-06-22", "Meatloaf")
        repo.assignToDate(item.id, "2026-06-24")

        val confirmed = logDao.entries.single().copy(confirmed = true)
        logDao.update(confirmed)
        val confirmedLoggedAt = confirmed.loggedAt

        repo.assignToDate(item.id, "2026-06-25")
        assertEquals(confirmedLoggedAt, logDao.entries.single().loggedAt)
    }

    @Test
    fun `unassign clears the day and removes the unconfirmed placeholder entry`() = runTest {
        val (repo, _, logDao) = buildRepo()
        val item = repo.createMenuItem("2026-06-22", "Meatloaf")
        repo.assignToDate(item.id, "2026-06-24")

        val unassigned = repo.unassign(item.id)!!
        assertNull(unassigned.assignedDate)
        assertTrue(logDao.entries.isEmpty())
    }

    @Test
    fun `unassign keeps a confirmed entry as real history`() = runTest {
        val (repo, _, logDao) = buildRepo()
        val item = repo.createMenuItem("2026-06-22", "Meatloaf")
        repo.assignToDate(item.id, "2026-06-24")
        logDao.update(logDao.entries.single().copy(confirmed = true))

        repo.unassign(item.id)
        assertEquals(1, logDao.entries.size)
    }

    @Test
    fun `delete removes the menu item and its unconfirmed placeholder`() = runTest {
        val (repo, menuDao, logDao) = buildRepo()
        val item = repo.createMenuItem("2026-06-22", "Meatloaf")
        repo.assignToDate(item.id, "2026-06-24")

        repo.delete(item.id)
        assertNull(menuDao.getById(item.id))
        assertTrue(logDao.entries.isEmpty())
    }
}
