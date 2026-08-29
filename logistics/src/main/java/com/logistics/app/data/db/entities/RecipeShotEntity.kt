package com.logistics.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A screenshot kept with a LifeOps recipe. Like [PantryItemEntity]'s food link, [recipeId] is a
 * soft, plain id rather than a Room foreign key — the recipe lives in LifeOps' database and this
 * row in Logistics', two stores in one process — so it is resolved at read time and a recipe that
 * gets deleted leaves an orphan row rather than a broken constraint (the repository sweeps those).
 *
 * [fileName] names a JPEG in `filesDir/recipe-shots/`. The bytes stay out of the database on
 * purpose: a screenshot is a megabyte or two, and `logistics.db` is copied whole every time anybody
 * takes a backup. See [com.logistics.app.data.store.RecipeShotStore].
 */
@Entity(
    tableName = "recipe_shots",
    indices = [Index("recipeId")]
)
data class RecipeShotEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val fileName: String,
    val sortOrder: Int,
    val createdAt: String
)
