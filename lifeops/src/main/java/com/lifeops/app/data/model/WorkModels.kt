package com.lifeops.app.data.model



/**
 * The week and the work in it: aspects, categories, tasks, the time logged against them,
 * and the sealed snapshot a closed week becomes.
 */

enum class Priority(val label: String, val baseValue: Int) {
    LOW("low", 5),
    MEDIUM("medium", 10),
    HIGH("high", 20),
    CRITICAL("critical", 35);

    companion object {
        fun from(value: String) = entries.firstOrNull { it.label == value } ?: MEDIUM
    }
}

enum class TaskStatus(val value: String) {
    PENDING("pending"),
    COMPLETED("completed"),
    SKIPPED("skipped"),
    INCOMPLETE("incomplete"),
    EXPIRED("expired"),
    CARRIED_FORWARD("carried_forward"),
    UNSUCCESSFUL("unsuccessful"),

    // Due date falls beyond the current week: the task waits in the Future Tasks queue
    // (Planning tab), rides along on week close, and flips to PENDING once its week arrives.
    QUEUED("queued");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.value == value } ?: PENDING
    }
}

enum class CarryForwardReason(val value: String, val label: String) {
    INTERNAL("internal", "I'm delaying"),
    EXTERNAL("external", "Other party is delaying");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value }
    }
}

data class Aspect(
    val id: String,
    val name: String,
    val color: String,
    val icon: String,
    val isArchived: Boolean = false
)

data class Category(
    val id: String,
    val aspectId: String,
    val name: String,
    val isArchived: Boolean = false
)

data class Week(
    val id: String,
    val startDate: String,
    val endDate: String,
    val isClosed: Boolean = false,
    val closedAt: String? = null
)

data class Task(
    val id: String,
    val weekId: String,
    val title: String,
    val aspectId: String? = null,
    val categoryId: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val dueDate: String? = null,
    val hardDeadline: Boolean = false,
    val status: TaskStatus = TaskStatus.PENDING,
    val resourceValue: Int = 10,
    val completedAt: String? = null,
    val carriedFromTaskId: String? = null,
    val createdAt: String,
    val isRecurring: Boolean = false,
    val estimatedMinutes: Int? = null,
    val carriedCount: Int = 0,
    val sortOrder: Int = 0,
    val isManuallyAdded: Boolean = false,
    val operationId: String? = null,
    val source: TaskSource = TaskSource.MANUAL,
    val slug: String = "",
    val carryForwardReason: CarryForwardReason? = null,
    val counterId: String? = null,
    // See TaskEntity: week-interval cadence (recurrenceDayOfMonth == null) or monthly-by-date.
    val recurrenceIntervalWeeks: Int = 1,
    val recurrenceDayOfMonth: Int? = null,
    /** One of the few tasks the week is actually judged on. See [TaskEntity.isCommitment]. */
    val isCommitment: Boolean = false
)

data class BusyBlock(
    val id: String,
    val title: String,
    val startMinutes: Int,
    val endMinutes: Int,
    // Weekly recurrence bitmask (bit 0 = Monday … bit 6 = Sunday); 0 when one-off.
    val daysMask: Int,
    // yyyy-MM-dd for a one-off block; null = weekly-recurring via daysMask.
    val specificDate: String? = null,
    // null = the user's own schedule; otherwise the person whose schedule this belongs to.
    val personId: String? = null,
    val createdAt: String,
    // When true (own-schedule blocks only), a reminder notification fires as the block starts —
    // once for a one-off, every matching day for a weekly block.
    val reminderEnabled: Boolean = false,
    // Google Calendar linkage; see BusyBlockEntity. Null until synced.
    val googleEventId: Long? = null,
    val googleCalendarId: Long? = null,
    // People tagged on this event, hydrated from the busy_block_people join table by
    // BusyBlockRepository (not a column on the entity — see BusyBlockRepository.upsert).
    val peopleIds: List<String> = emptyList()
)

data class TaskAttachment(
    val id: String,
    val taskId: String,
    val imageData: String,
    val caption: String? = null,
    val createdAt: String
)

data class CarryForwardEntry(
    val taskTitle: String,
    val carriedCount: Int,
    val finalStatus: TaskStatus,
    val weekLabel: String
)

data class WeekProgress(
    val completedCount: Int,
    val totalCount: Int,
    val totalTimeMinutes: Int,
    /** Tasks marked as this week's bar, and how many are done — the line that licenses the weekend. */
    val commitmentTotal: Int = 0,
    val commitmentCompleted: Int = 0
) {
    /** True once every commitment is done — and only when a bar was actually set. */
    val commitmentMet: Boolean get() = commitmentTotal > 0 && commitmentCompleted >= commitmentTotal
}

data class TaskNote(
    val id: String,
    val taskId: String,
    val content: String,
    val createdAt: String,
    val subtaskId: String? = null
)

data class TimeEntry(
    val id: String,
    val taskId: String,
    val durationMinutes: Int,
    val note: String? = null,
    val recordedAt: String,
    val subtaskId: String? = null
)

data class WeekSnapshot(
    val id: String,
    val weekId: String,
    val completedCount: Int,
    val incompleteCount: Int,
    val expiredCount: Int,
    val skippedCount: Int,
    val carriedForwardCount: Int,
    val unsuccessfulCount: Int,
    val totalResourcesEarned: Int,
    val aspectBreakdown: Map<String, Int>,
    val categoryBreakdown: Map<String, Int>,
    val categorySlipBreakdown: Map<String, Int>,
    val categoryTotalBreakdown: Map<String, Int>,
    val hardDeadlineCompletedCount: Int,
    val hardDeadlineExpiredCount: Int,
    val createdAt: String,
    /** aspectId -> sealed {minutes, name, colour}. Drives the Growth rings for closed weeks. */
    val aspectHistory: Map<String, AspectHistoryEntry> = emptyMap(),
    val selfRating: Int? = null,
    val selfRatingNote: String? = null,
    /** "Mental reset achieved?" at close. Null = the prompt was skipped, not answered "no". */
    val mentalReset: Boolean? = null,
    /** Overall exhaustion at close, 1 (fresh) → 10 (wiped out). Null = skipped. */
    val exhaustion: Int? = null,
    val subtaskTickCount: Int = 0,
    /** How many tasks were marked as this week's bar. Zero = no bar was set that week. */
    val commitmentTotal: Int = 0,
    /** How many of those were completed. Only meaningful when [commitmentTotal] > 0. */
    val commitmentCompleted: Int = 0
)

enum class TaskSource {
    MANUAL, PLANNED;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: MANUAL
    }
}

data class CarryoverSummaryRow(
    val taskTitle: String,
    val carriedCount: Int,
    val originWeekLabel: String,
    val completionWeekLabel: String,
    val lineageMinutes: Int,
    val pointsEarned: Int,
    val isStillOpen: Boolean   // true = PENDING, false = COMPLETED
)
