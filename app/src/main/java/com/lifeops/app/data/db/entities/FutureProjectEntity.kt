package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "future_projects")
data class FutureProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAt: String,
    val updatedAt: String
)
