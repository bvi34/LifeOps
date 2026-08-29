package com.lifeops.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = WeekEntity::class,
            parentColumns = ["id"],
            childColumns = ["weekId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AspectEntity::class,
            parentColumns = ["id"],
            childColumns = ["aspectId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("weekId"), Index("aspectId"), Index("categoryId"),
        Index("createdAt"), Index("completedAt"), Index("status"),
        Index("projectId"), Index("source"), Index("counterId"),
        // Backs the per-week slug dedup query (TaskDao.getSlugsByWeek) and matches the
        // index MIGRATION_14_15 creates as index_tasks_weekId_slug. Must be declared here
        // or Room's post-migration schema validation crashes on launch for upgrading users.
        Index(value = ["weekId", "slug"])
    ]
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val weekId: String,
    val title: String,
    val aspectId: String? = null,
    val categoryId: String? = null,
    val priority: String = "medium",
    val dueDate: String? = null,
    val hardDeadline: Boolean = false,
    val status: String = "pending",
    val resourceValue: Int = 10,
    val completedAt: String? = null,
    val carriedFromTaskId: String? = null,
    val createdAt: String,
    @ColumnInfo(defaultValue = "0")
    val isRecurring: Boolean = false,
    val estimatedMinutes: Int? = null,
    @ColumnInfo(defaultValue = "0")
    val carriedCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val isManuallyAdded: Boolean = false,
    val projectId: String? = null,
    @ColumnInfo(name = "source", defaultValue = "MANUAL")
    val source: String = "MANUAL",
    @ColumnInfo(defaultValue = "")
    val slug: String = "",
    val carryForwardReason: String? = null,
    // Optional counter this task ticks when completed (no FK — mirrors projectId; counters are
    // archived, never deleted, so the reference can't dangle).
    val counterId: String? = null,
    // Recurrence cadence (only meaningful when isRecurring = true). Two modes:
    //   • Week-interval: recurrenceDayOfMonth is null and the task reappears every
    //     recurrenceIntervalWeeks weeks (1 = weekly, 2 = bi-weekly, …).
    //   • Monthly-by-date: recurrenceDayOfMonth is set (1–31, clamped to the month's
    //     length) and the task reappears in whichever week contains that calendar day;
    //     recurrenceIntervalWeeks is ignored.
    // Legacy recurring tasks default to weekly (interval 1), preserving prior behaviour.
    @ColumnInfo(defaultValue = "1")
    val recurrenceIntervalWeeks: Int = 1,
    val recurrenceDayOfMonth: Int? = null,
    // The week's bar: this task is one of the few whose completion decides whether the week
    // succeeded. Deliberately NOT part of scoring — marking a task as a commitment mints nothing
    // extra, or the flag would become a second economy and every task would end up wearing it.
    // It moves one thing only: what the week reads back as at close.
    @ColumnInfo(defaultValue = "0")
    val isCommitment: Boolean = false
)
