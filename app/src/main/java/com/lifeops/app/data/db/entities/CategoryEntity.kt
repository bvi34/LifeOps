package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = AspectEntity::class,
            parentColumns = ["id"],
            childColumns = ["aspectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("aspectId")]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val aspectId: String,
    val name: String,
    val isArchived: Boolean = false
)
