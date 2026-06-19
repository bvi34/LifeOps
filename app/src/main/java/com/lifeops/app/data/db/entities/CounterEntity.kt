package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "counters",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("categoryId")]
)
data class CounterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: String
)
