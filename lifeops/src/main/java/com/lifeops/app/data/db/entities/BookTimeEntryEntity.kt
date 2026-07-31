package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "book_time_entries",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId"), Index("recordedAt")]
)
data class BookTimeEntryEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val durationMinutes: Int,
    val note: String?,
    val recordedAt: String
)
