package com.logistics.app.data.repository

import com.logistics.app.data.db.dao.RecipeNoteDao
import com.logistics.app.data.db.entities.RecipeNoteEntity
import com.logistics.app.data.model.RecipeNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * The notes and reviews kept against recipes.
 *
 * Everything here writes to Logistics' own `recipe_notes`, never to the recipe: LifeOps' book is
 * shared by the whole suite, and an opinion about a recipe is not part of it. That is why a note can
 * be written, rewritten, or dropped without the recipe ever being touched — and why deleting every
 * note of a recipe ([removeForRecipe]) leaves the recipe standing.
 *
 * Two rules the callers rely on:
 *  - **A note must say something.** Blank text with no rating is refused rather than stored, so an
 *    accidental tap on "Save" doesn't leave an empty row in the cooking log.
 *  - **A rating is 1–5 or nothing.** Anything else is clamped into range, and zero means "no
 *    verdict" rather than "one star" — the summary averages only the notes that carry one.
 */
class RecipeNoteRepository(private val dao: RecipeNoteDao) {

    fun observeForRecipe(recipeId: String): Flow<List<RecipeNote>> =
        dao.observeForRecipe(recipeId).map { list -> list.map { it.toModel() } }

    /** Every note, grouped by recipe — what the Recipes list needs to show a rating per row. */
    fun observeAll(): Flow<Map<String, List<RecipeNote>>> =
        dao.observeAll().map { list -> list.map { it.toModel() }.groupBy { it.recipeId } }

    suspend fun getForRecipe(recipeId: String): List<RecipeNote> = dao.getForRecipe(recipeId).map { it.toModel() }

    /**
     * Writes a new note. Returns the stored note, or null when there was nothing to store — no text
     * and no rating.
     */
    suspend fun add(recipeId: String, text: String, rating: Int? = null): RecipeNote? {
        val body = text.trim()
        val stars = normalizeRating(rating)
        if (body.isEmpty() && stars == null) return null
        val now = Instant.now().toString()
        val note = RecipeNote(
            id = UUID.randomUUID().toString(),
            recipeId = recipeId,
            rating = stars,
            text = body,
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(note.toEntity())
        return note
    }

    /**
     * Rewrites a note in place, keeping its [RecipeNote.createdAt] so it stays where it sits in the
     * log. Returns the updated note, null if there is no such note or the edit emptied it out —
     * clearing a note to nothing is [remove]'s job, and is not done silently here.
     */
    suspend fun update(noteId: String, text: String, rating: Int?): RecipeNote? {
        val existing = dao.getById(noteId) ?: return null
        val body = text.trim()
        val stars = normalizeRating(rating)
        if (body.isEmpty() && stars == null) return null
        val updated = existing.copy(rating = stars, text = body, updatedAt = Instant.now().toString())
        dao.upsert(updated)
        return updated.toModel()
    }

    suspend fun remove(noteId: String) = dao.delete(noteId)

    /** Drops every note of a recipe — for when the recipe itself is gone from LifeOps' book and the
     *  notes are left pointing at nothing. */
    suspend fun removeForRecipe(recipeId: String) = dao.deleteForRecipe(recipeId)

    private fun normalizeRating(rating: Int?): Int? = rating?.takeIf { it > 0 }?.coerceIn(MIN_STARS, MAX_STARS)

    private fun RecipeNoteEntity.toModel() = RecipeNote(
        id = id,
        recipeId = recipeId,
        rating = rating,
        text = text,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun RecipeNote.toEntity() = RecipeNoteEntity(
        id = id,
        recipeId = recipeId,
        rating = rating,
        text = text,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        const val MIN_STARS = 1
        const val MAX_STARS = 5
    }
}
