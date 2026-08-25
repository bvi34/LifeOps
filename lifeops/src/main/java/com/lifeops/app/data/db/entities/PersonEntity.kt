package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A person in the household. Carries the weather-comfort preferences the roadmap's Phase 4
 * (household profiles) keys off — feels-like tolerances, UV/wind/rain ceilings, sun sensitivity —
 * plus a freeform activity-preferences note. Standalone table; timeline notes live in
 * person_notes and task involvement in task_people, so this row stays a plain profile.
 *
 * Nullable preference columns mean "no opinion" — a null ceiling never rules a time slot out.
 */
@Entity(tableName = "persons", indices = [Index("syncVersion")])
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
    val relationship: String? = null,
    /** Contact identity for Google Calendar attendee matching (see GoogleCalendarSyncRepository). */
    val email: String? = null,
    val phone: String? = null,
    /**
     * The identity this person keeps across the People sync seam, as distinct from [id], which is
     * only this database's row id. Null until the first sync stamps one (see
     * [com.lifeops.app.data.repository.PeopleSyncRepository]); MIGRATION_51_52 backfills it from
     * [id] for everyone who was already here.
     */
    val personKey: String? = null,
    /**
     * This peer's monotonic stamp: bumped by every *local* edit, left alone by every write that
     * arrived over the seam. The outbound envelope is "every row above the peer's ack", so the
     * outbox is derived from these rows rather than kept as a separate queue that could disagree
     * with them after a crash.
     */
    @ColumnInfo(defaultValue = "0")
    val syncVersion: Long = 0L,
    /** The merge clock, epoch millis. Zero means "we don't know", not "the beginning of time". */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0L
)
