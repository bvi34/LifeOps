package com.logistics.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note somebody wrote *about* a recipe — how it turned out, what they'd change next time, a star
 * rating — as opposed to the recipe itself. The distinction is the whole point of the table: a
 * recipe's ingredients and method are what the source said, shared with LifeOps and everything else
 * in the suite, and they should not gain "used half the salt, doubled the garlic" as an extra step.
 *
 * Like [RecipeShotEntity], [recipeId] is a soft, plain id rather than a Room foreign key — the
 * recipe lives in LifeOps' database and this row in Logistics', two stores in one process — so it is
 * resolved at read time, and a recipe deleted out from under a note leaves an orphan row rather than
 * a broken constraint (the repository sweeps those).
 *
 * [rating] is 1–5 stars, or null: plenty of notes are "needs 10 more minutes" with no verdict
 * attached, and a missing rating must not read as a bad one. [text] may be blank *only* when a
 * rating is present — a note that says nothing and rates nothing is not saved.
 */
@Entity(
    tableName = "recipe_notes",
    indices = [Index("recipeId")]
)
data class RecipeNoteEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val rating: Int?,
    val text: String,
    val createdAt: String,
    val updatedAt: String
)
