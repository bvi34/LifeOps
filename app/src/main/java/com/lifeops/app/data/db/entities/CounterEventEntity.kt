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
    val note: String? = null
)
