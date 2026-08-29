package com.logistics.app.data.repository

import android.net.Uri
import com.logistics.app.data.db.dao.RecipeShotDao
import com.logistics.app.data.db.entities.RecipeShotEntity
import com.logistics.app.data.model.RecipeShot
import com.logistics.app.data.store.RecipeShotStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * The screenshots kept with recipes: the row in `logistics.db` and the JPEG in
 * [RecipeShotStore], kept in step.
 *
 * The pairing has one rule, and it is the same one Health's document store follows: **the file is
 * written before the row that names it, and deleted after the row that named it is gone.** Either
 * order can fail halfway; only this one fails towards an orphaned file (a little wasted space)
 * rather than a recipe that claims a picture which isn't there.
 */
class RecipeShotRepository(
    private val dao: RecipeShotDao,
    private val store: RecipeShotStore
) {

    fun observeForRecipe(recipeId: String): Flow<List<RecipeShot>> =
        dao.observeForRecipe(recipeId).map { list -> list.map { it.toModel() } }

    /** Every shot, grouped by recipe — what the Recipes list needs to show a thumbnail per row. */
    fun observeAll(): Flow<Map<String, List<RecipeShot>>> =
        dao.observeAll().map { list -> list.map { it.toModel() }.groupBy { it.recipeId } }

    suspend fun getForRecipe(recipeId: String): List<RecipeShot> = dao.getForRecipe(recipeId).map { it.toModel() }

    /** The decoded picture for a row, or null when the file has gone missing under it. */
    fun bitmapFor(shot: RecipeShot) = store.load(shot.fileName)

    /**
     * Attach pictures to [recipeId], in the order given. Each is downsampled into the store first;
     * one that can't be read is skipped rather than failing the rest. Returns how many landed.
     */
    suspend fun attach(recipeId: String, sources: List<Uri>): Int {
        var next = dao.getForRecipe(recipeId).size
        var saved = 0
        for (source in sources) {
            val fileName = store.save(source) ?: continue
            dao.upsert(
                RecipeShotEntity(
                    id = UUID.randomUUID().toString(),
                    recipeId = recipeId,
                    fileName = fileName,
                    sortOrder = next,
                    createdAt = Instant.now().toString()
                )
            )
            next++
            saved++
        }
        return saved
    }

    /** Drops one screenshot: the row first, then the file it named. */
    suspend fun remove(shotId: String) {
        val shot = dao.getById(shotId) ?: return
        dao.delete(shotId)
        store.delete(shot.fileName)
    }

    /**
     * Drops every screenshot of a recipe — for when the recipe itself is gone from LifeOps' book and
     * the pictures are left pointing at nothing.
     */
    suspend fun removeForRecipe(recipeId: String) {
        val shots = dao.getForRecipe(recipeId)
        dao.deleteForRecipe(recipeId)
        shots.forEach { store.delete(it.fileName) }
    }

    private fun RecipeShotEntity.toModel() = RecipeShot(
        id = id,
        recipeId = recipeId,
        fileName = fileName,
        sortOrder = sortOrder,
        createdAt = createdAt
    )
}
