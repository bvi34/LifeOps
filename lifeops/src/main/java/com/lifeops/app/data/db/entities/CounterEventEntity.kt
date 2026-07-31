package com.lifeops.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "counter_events",
    foreignKeys = [
        ForeignKey(
            entity = CounterEntity::class,
            parentColumns = ["id"],
            childColumns = ["counterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // (counterId, weekKey) backs the weekly-window and per-counter trend queries and also
    // satisfies Room's foreign-key index requirement (counterId is the leading column).
    // occurredAt backs the timestamped-detail ordering.
    indices = [
        Index(value = ["counterId", "weekKey"]),
        Index("occurredAt")
    ]
)
data class CounterEventEntity(
    @PrimaryKey val id: String,
    val counterId: String,
    // Stamped from occurredAt (not "now") so backdated events land in the right week.
    val weekKey: Int,
    val occurredAt: String,
    val delta: Int = 1,
    val note: String? = null,
    // --- Weather at the moment of the tick (all nullable) ---------------------------------------
    // Captured from the freshest cached conditions for the user's primary weather location when a
    // *live* tick is logged (never backdated/bulk entries — we don't have historical weather, and
    // stamping "now" onto a past day would lie). All null when no location is tracked, nothing has
    // been cached yet, the cache is stale, or the tick was backdated. No @ColumnInfo(defaultValue)
    // on any of these, so MIGRATION_40_41 must add them as plain nullable columns (no SQL DEFAULT)
    // to match Room's generated schema exactly — same rule the rest of this schema follows.
    val weatherTempF: Int? = null,
    val weatherFeelsLikeF: Int? = null,
    val weatherHumidityPct: Int? = null,
    val weatherWindMph: Int? = null,
    val weatherConditions: String? = null,
    val weatherLocationName: String? = null,
    // The snapshot's observedAt, so the UI can show how current the reading actually was.
    val weatherObservedAt: String? = null
)
