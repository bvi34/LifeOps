package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What was measured and what was observed: readings and symptoms.
 */

/**
 * One measurement. [type] says which; [value] is always in the canonical unit for that type
 * (temperature in °C, weight in kg, pressure in mmHg), with [secondaryValue] carrying diastolic for
 * blood pressure and nothing else. [site] is only meaningful for temperature and is what makes an
 * armpit reading comparable to an ear one.
 */
@Entity(
    tableName = "readings",
    indices = [Index("profileId"), Index("takenAt"), Index("type"), Index("episodeId")]
)
data class ReadingEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val episodeId: String?,
    val type: String,
    val value: Double,
    val secondaryValue: Double?,
    val site: String?,
    val takenAt: Long,
    val note: String?,
    val createdAt: Long
)

/** One symptom, from when it started until it stops ([endedAt] null while it's still going). */
@Entity(
    tableName = "symptoms",
    indices = [Index("profileId"), Index("episodeId"), Index("startedAt")]
)
data class SymptomEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val episodeId: String?,
    val name: String,
    /** 1–5, mild to severe. A number you can chart beats an adjective you can't. */
    val severity: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val note: String?,
    /** When the row was written, as against when the symptom started. See the note in [DoseEntity]. */
    val createdAt: Long? = null
)
