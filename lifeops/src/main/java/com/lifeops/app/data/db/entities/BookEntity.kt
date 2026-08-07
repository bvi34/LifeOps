package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    // The user-editable GUI title. Seeded from Citation's title when the book is first ingested,
    // but owned by the user thereafter — sync never overwrites it, so renaming here is safe.
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
    val category: String? = null,
    // The Citation record: the source's own id (O'Reilly product id / ISBN / Royal Road id) and the
    // title Citation reports. Kept separate from the GUI [title] so the user can rename freely while
    // this preserves what Citation/O'Reilly calls it. Refreshed on every sync; null for LifeOps-only
    // books and for records Citation hasn't supplied an id/title for.
    val sourceId: String? = null,
    val citationTitle: String? = null
)
