package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "week_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = WeekEntity::class,
            parentColumns = ["id"],
            childColumns = ["weekId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("weekId")]
)
data class WeekSnapshotEntity(
    @PrimaryKey val id: String,
    val weekId: String,
    val completedCount: Int,
    val incompleteCount: Int,
    val expiredCount: Int,
    val skippedCount: Int,
    val carriedForwardCount: Int,
    @ColumnInfo(defaultValue = "0")
    val unsuccessfulCount: Int = 0,
    val totalResourcesEarned: Int,
    val aspectBreakdown: String,
    val categoryBreakdown: String,
    // categoryId → (incomplete + expired) count for slip rate
    val categorySlipBreakdown: String,
    val categoryTotalBreakdown: String,
    val hardDeadlineCompletedCount: Int,
    val hardDeadlineExpiredCount: Int,
    val createdAt: String,
    // JSON: aspectId -> { minutes, name, colorHex }. Sealed at week-close so the Growth
    // Record rings survive an aspect being deleted/renamed/recoloured. Defaults to "{}"
    // for snapshots written before this column existed (backfilled on first launch).
    @ColumnInfo(defaultValue = "'{}'")
    val aspectHistory: String = "{}",
    val selfRating: Int? = null,
    val selfRatingNote: String? = null,
    // Phase 9: flat +1 per subtask check at week-close; no modifier, independent of task scoring
    @ColumnInfo(defaultValue = "0")
    val subtaskTickCount: Int = 0
)
