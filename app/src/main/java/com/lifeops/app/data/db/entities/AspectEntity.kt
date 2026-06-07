package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "aspects")
data class AspectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val icon: String,
    val isArchived: Boolean = false
)
