package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    // BookStatus: TO_READ | READING | DONE
    val status: String,
    val createdAt: String,
    val completedAt: String?
)
