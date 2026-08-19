package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A person in the household. Carries the weather-comfort preferences the roadmap's Phase 4
 * (household profiles) keys off — feels-like tolerances, UV/wind/rain ceilings, sun sensitivity —
 * plus a freeform activity-preferences note. Standalone table; timeline notes live in
 * person_notes and task involvement in task_people, so this row stays a plain profile.
 *
 * Nullable preference columns mean "no opinion" — a null ceiling never rules a time slot out.
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Avoid when the feels-like temperature is above this (°F). */
    val heatToleranceMaxF: Int?,
    /** Avoid when the feels-like temperature is below this (°F). */
    val coldToleranceMinF: Int?,
    /** Avoid when the UV index is above this. */
    val uvMax: Int?,
    /** Avoid when sustained wind is above this (mph). */
    val windMaxMph: Int?,
    /** Avoid when rain probability is above this (%). */
    val maxPrecipitationPct: Int?,
    /** Sun sensitivity bucket (SunSensitivity.value). */
    val sunSensitivity: String,
    /** Freeform "likes hiking, hates crowds" style note. */
    val activityPreferences: String?,
    val isArchived: Boolean,
    val sortOrder: Int,
    val createdAt: String,
    /** Relationship.value, or null to exclude this person from relationship-balance analytics. */
    val relationship: String? = null
)
