package com.lifeops.app.data.model

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
    CARRIED_FORWARD("carried_forward");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.value == value } ?: PENDING
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
    val createdAt: String
)

data class TaskNote(
    val id: String,
    val taskId: String,
    val content: String,
    val createdAt: String
)

data class TimeEntry(
    val id: String,
    val taskId: String,
    val durationMinutes: Int,
    val note: String? = null,
    val recordedAt: String
)

data class GameResource(
    val id: String,
    val name: String,
    val currentValue: Int = 0,
    val lifetimeEarned: Int = 0,
    val slotIndex: Int
)

data class GameResourceMapping(
    val id: String,
    val gameResourceId: String,
    val aspectId: String,
    val weight: Float = 1.0f
)

data class ResourceTransaction(
    val id: String,
    val resourceId: String,
    val amount: Int,
    val type: String,
    val note: String? = null,
    val createdAt: String
)

data class WeekSnapshot(
    val id: String,
    val weekId: String,
    val completedCount: Int,
    val incompleteCount: Int,
    val expiredCount: Int,
    val skippedCount: Int,
    val carriedForwardCount: Int,
    val totalResourcesEarned: Int,
    val aspectBreakdown: Map<String, Int>,
    val categoryBreakdown: Map<String, Int>,
    val categorySlipBreakdown: Map<String, Int>,
    val categoryTotalBreakdown: Map<String, Int>,
    val hardDeadlineCompletedCount: Int,
    val hardDeadlineExpiredCount: Int,
    val createdAt: String
)

data class ImportPreview(
    val newTasks: List<Task>,
    val newAspects: List<Aspect>,
    val newCategories: List<Category>,
    val existingTaskCount: Int
)
