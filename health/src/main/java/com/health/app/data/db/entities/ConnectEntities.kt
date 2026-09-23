package com.health.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What Health Connect held about the household's primary user, as it was imported.
 */

/**
 * One record read from Health Connect — a night's sleep, an hour of steps, a weight, a meal, a lab
 * result.
 *
 * [id] is Health Connect's own id for the record (for a medical record, one built from its data
 * source and FHIR id), which is what lets a later import update or delete the same row instead of
 * adding a second copy. [kind] is a `logic/ConnectKind` key; [value] is its headline number in that
 * kind's unit, and [secondaryValue] is the diastolic for a blood pressure and nothing else.
 *
 * [detail] is everything else the record carried, as JSON: sleep stages, heart-rate samples,
 * exercise laps, the nutrients in a meal, the FHIR resource of a medical record. Kept whole, so
 * nothing Health Connect held is lost by passing through here.
 *
 * [profileId] is whoever was the primary user when the record was imported. Health Connect's data
 * is one person's — the phone's — and the primary user is Health's answer to which person that is.
 */
@Entity(
    tableName = "connect_records",
    indices = [Index(value = ["profileId", "kind", "startAt"]), Index("startAt")]
)
data class ConnectRecordEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val kind: String,
    val startAt: Long,
    /** Null for a record that is one moment rather than a stretch of time. */
    val endAt: Long?,
    /** The UTC offset where it was recorded, so a day can be read in the day it happened in. */
    val zoneOffsetSeconds: Int?,
    val value: Double?,
    val secondaryValue: Double?,
    val detail: String?,
    /** The package that wrote it to Health Connect — "com.fitbit.FitbitMobile". */
    val source: String?,
    /** The device that measured it, when the writer said: "Google Pixel Watch 2". */
    val device: String?,
    /** When Health Connect last saw it change. */
    val modifiedAt: Long,
    val importedAt: Long
)

/** How many records of one kind a person has, and when the newest one was. Not a table. */
data class ConnectKindCount(val kind: String, val count: Int, val latestAt: Long?)
