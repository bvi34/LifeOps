package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val servings: Double,
    val createdAt: String,
    // Both nullable: recipes written before v51 have neither, and a hand-entered recipe never
    // has a source. Instructions are free text, one step per line.
    val instructions: String? = null,
    val sourceUrl: String? = null
)
