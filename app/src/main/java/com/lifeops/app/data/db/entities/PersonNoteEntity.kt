package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One timeline note about a person — same append-only shape as future_project_notes / book_notes.
 * Cascades away when the person is deleted.
 */
@Entity(
    tableName = "person_notes",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId")]
)
data class PersonNoteEntity(
    @PrimaryKey val id: String,
    val personId: String,
    val content: String,
    val createdAt: String
)
