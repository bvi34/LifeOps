package com.repository.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One document Repository is holding.
 *
 * The bytes are **not** here. [fileName] names a file under `filesDir/documents/`, exactly as
 * Health's own store does it and for the same reasons: a database that is copied whole by every
 * backup should not carry a folder of PDFs, and a document should survive being looked at.
 *
 * [ownerApp] / [ownerKey] / [ownerLabel] are how a document knows what it is about — see
 * `logic/DocumentOwner`. All three null means the household's own drawer, which is a real place and
 * not a missing value.
 */
@Entity(
    tableName = "documents",
    indices = [Index("ownerApp"), Index("ownerKey"), Index("kind"), Index("addedAt")]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** `logic/DocumentKind`'s key. A label for grouping, never a behaviour. */
    val kind: String,
    val ownerApp: String?,
    val ownerKey: String?,
    /** What the owning app calls the thing this is about, as of the last time it said so. */
    val ownerLabel: String?,
    /** A bare file name under `documents/`, never a path and never bytes. */
    val fileName: String,
    /** As stored — an attached photo is a JPEG whatever it arrived as. */
    val mimeType: String?,
    val sizeBytes: Long?,
    val note: String?,
    val addedAt: Long,
    val updatedAt: Long
)
