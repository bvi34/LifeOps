package com.lifeops.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lifeops.app.data.db.entities.RecipeEntity
import com.lifeops.app.data.db.entities.RecipeIngredientEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY name")
    fun observeAll(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes")
    suspend fun getAll(): List<RecipeEntity>

    @Query("SELECT * FROM recipe_ingredients")
    suspend fun getAllIngredients(): List<RecipeIngredientEntity>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getById(id: String): RecipeEntity?

    @Query("SELECT * FROM recipes WHERE id = :id")
    fun observeById(id: String): Flow<RecipeEntity?>

    // @Upsert updates in place; @Insert(REPLACE) would delete-and-reinsert the recipe,
    // cascading away its ingredients.
    @Upsert
    suspend fun upsert(recipe: RecipeEntity)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :recipeId ORDER BY sortOrder")
    suspend fun getIngredients(recipeId: String): List<RecipeIngredientEntity>

    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :recipeId ORDER BY sortOrder")
    fun observeIngredients(recipeId: String): Flow<List<RecipeIngredientEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIngredient(ingredient: RecipeIngredientEntity)

    @Query("DELETE FROM recipe_ingredients WHERE id = :id")
    suspend fun deleteIngredient(id: String)
}
