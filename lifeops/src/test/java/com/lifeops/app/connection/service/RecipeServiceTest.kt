package com.lifeops.app.connection.service

import com.lifeops.app.data.db.dao.FoodItemDao
import com.lifeops.app.data.db.dao.RecipeDao
import com.lifeops.app.data.db.entities.FoodItemEntity
import com.lifeops.app.data.db.entities.RecipeEntity
import com.lifeops.app.data.db.entities.RecipeIngredientEntity
import com.lifeops.app.data.repository.RecipeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The edit rule that keeps a partial write from wiping a recipe: **null leaves a field alone, blank
 * clears it**. A script (or an older route call) that only knows about `name` must not be able to
 * delete the method by omission.
 */
class RecipeServiceTest {

    private class FakeRecipeDao : RecipeDao {
        val recipes = mutableListOf<RecipeEntity>()
        val ingredients = mutableListOf<RecipeIngredientEntity>()
        override fun observeAll(): Flow<List<RecipeEntity>> = flowOf(recipes)
        override suspend fun getAll(): List<RecipeEntity> = recipes.toList()
        override suspend fun getAllIngredients(): List<RecipeIngredientEntity> = ingredients.toList()
        override suspend fun getById(id: String): RecipeEntity? = recipes.firstOrNull { it.id == id }
        override fun observeById(id: String): Flow<RecipeEntity?> = flowOf(recipes.firstOrNull { it.id == id })
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
        override suspend fun getById(id: String): FoodItemEntity? = null
        override suspend fun search(query: String, limit: Int): List<FoodItemEntity> = emptyList()
        override suspend fun upsert(item: FoodItemEntity) = Unit
        override suspend fun upsertAll(items: List<FoodItemEntity>) = Unit
        override suspend fun getAll(): List<FoodItemEntity> = emptyList()
        override suspend fun getByFdcId(fdcId: Long): FoodItemEntity? = null
        override suspend fun delete(id: String) = Unit
        override suspend fun countBySource(source: String): Int = 0
        override suspend fun count(): Int = 0
    }

    private fun service() = RecipeService(RecipeRepository(FakeRecipeDao(), FakeFoodItemDao()))

    @Test
    fun `create keeps the method and the source link`() = runTest {
        val service = service()
        val recipe = service.create(
            name = "Pancakes",
            servings = 4.0,
            instructions = "Mix.\nFry.",
            sourceUrl = "https://example.com/pancakes"
        )
        assertEquals("Mix.\nFry.", recipe.instructions)
        assertEquals("https://example.com/pancakes", recipe.sourceUrl)
    }

    @Test
    fun `create treats blank free text as absent`() = runTest {
        val recipe = service().create(name = "Toast", instructions = "   ", sourceUrl = "")
        assertNull(recipe.instructions)
        assertNull(recipe.sourceUrl)
    }

    @Test
    fun `update leaves omitted fields alone`() = runTest {
        val service = service()
        val recipe = service.create("Stew", 2.0, instructions = "Simmer.", sourceUrl = "https://example.com/stew")

        val renamed = service.update(recipe.id, name = "Beef stew")!!
        assertEquals("Beef stew", renamed.name)
        assertEquals("Simmer.", renamed.instructions)
        assertEquals("https://example.com/stew", renamed.sourceUrl)
        assertEquals(2.0, renamed.servings, 0.0001)
    }

    @Test
    fun `update clears a field the user emptied`() = runTest {
        val service = service()
        val recipe = service.create("Stew", instructions = "Simmer.", sourceUrl = "https://example.com/stew")

        val cleared = service.update(recipe.id, instructions = "", sourceUrl = "  ")!!
        assertNull(cleared.instructions)
        assertNull(cleared.sourceUrl)
    }

    @Test
    fun `update ignores a blank name rather than blanking the recipe`() = runTest {
        val service = service()
        val recipe = service.create("Stew")
        assertEquals("Stew", service.update(recipe.id, name = "   ")!!.name)
    }

    @Test
    fun `update returns null for an unknown recipe`() = runTest {
        assertNull(service().update("missing", name = "Anything"))
    }
}
