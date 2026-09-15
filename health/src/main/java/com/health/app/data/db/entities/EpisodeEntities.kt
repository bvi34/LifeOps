package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Illnesses, and the free-text notes kept during one.
 */

/**
 * A bout of illness — the thing readings, symptoms and doses hang off so they can be read back as
 * one story instead of a scatter of rows. Open while [endedAt] is null; one open episode per person
 * at a time is enforced by the repository, not the schema.
 */
@Entity(tableName = "episodes", indices = [Index("profileId"), Index("startedAt")])
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * The care log: fluids taken, a bath, a doctor's call, what they said. This is the "and such" of
 * looking after someone — the part you cannot reconstruct afterwards and always wish you had.
 */
@Entity(
    tableName = "care_notes",
    indices = [Index("profileId"), Index("episodeId"), Index("at")]
)
data class CareNoteEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val episodeId: String?,
    val kind: String,
    val text: String,
    val at: Long,
    /** When the row was written, as against when it happened. See the note in [DoseEntity]. */
    val createdAt: Long? = null
)
