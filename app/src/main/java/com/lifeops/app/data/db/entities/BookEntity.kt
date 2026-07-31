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
    val completedAt: String?,
    // Provenance from Citation telemetry: the source kind (EPUB/PDF/ROYAL_ROAD/OREILLY) and its
    // derived reading category (Learning/Fun). Nullable — books created directly in LifeOps have
    // neither. Drives the Learning-vs-Fun reading report; the economy treats all reading alike.
    val sourceType: String? = null,
    val category: String? = null
)
