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
    CARRIED_FORWARD("carried_forward"),
    UNSUCCESSFUL("unsuccessful");

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
    val projectId: String? = null,
    val source: TaskSource = TaskSource.MANUAL,
    val slug: String = "",
    val carryForwardReason: CarryForwardReason? = null,
    val counterId: String? = null
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
    val totalTimeMinutes: Int
)

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

/**
 * Per-aspect record sealed into a closed week's snapshot at week-close. Carries the
 * minutes spent plus the aspect's name + colour AS THEY WERE that week, so the Growth
 * Record ring stays faithful even if the aspect is later deleted, renamed or recoloured.
 */
data class AspectHistoryEntry(
    val minutes: Int,
    val name: String,
    val colorHex: String
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
    val subtaskTickCount: Int = 0
)

enum class ProjectStatus(val value: String) {
    ACTIVE("active"),
    COMPLETED("completed");
    companion object { fun from(value: String) = entries.firstOrNull { it.value == value } ?: ACTIVE }
}

data class Project(
    val id: String,
    val title: String,
    val aspectId: String? = null,
    val categoryId: String? = null,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val description: String? = null,
    val createdAt: String,
    val completedAt: String? = null
)

data class ProjectStats(
    val project: Project,
    val taskCount: Int,
    val completedCount: Int,
    val totalTimeMinutes: Int
)

data class Counter(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: String
)

data class CounterEvent(
    val id: String,
    val counterId: String,
    val weekKey: Int,
    val occurredAt: String,
    val delta: Int = 1,
    val note: String? = null
)

data class ScoringPoint(val weekLabel: String, val resourcesEarned: Int)

data class PriorityCompletionRow(
    val priority: Priority,
    val completedCount: Int,
    val totalCount: Int
) {
    val rate: Float get() = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
}

enum class ResourceResetCycle(val label: String) {
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    NEVER("never");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.label == value } ?: MONTHLY
    }
}

data class CostResource(
    val id: String,
    val name: String,
    val resetCycle: ResourceResetCycle = ResourceResetCycle.MONTHLY,
    val capacity: Int? = null,
    val isActive: Boolean = true,
    val sortIndex: Int = 0,
    val createdAt: String
)

data class TaskCostEntry(
    val id: String,
    val taskId: String,
    val resourceId: String,
    val amount: Int,
    val note: String? = null,
    val recordedAt: String
)

data class CostUsageRow(
    val resourceName: String,
    val resetCycle: ResourceResetCycle,
    val capacity: Int?,
    val totalAmount: Int
)

enum class TaskSource {
    MANUAL, PLANNED;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: MANUAL
    }
}

data class CustomPalette(
    val primary: String = "#BB86FC",
    val secondary: String = "#03DAC6",
    val tertiary: String = "#3700B3",
    val darkBackground: String = "#121212",
    val lightBackground: String = "#F5F5F5"
)

enum class ThemePreset(val displayName: String) {
    DEFAULT("Default"),
    BEACON("Beacon"),
    OCEAN("Ocean"),
    SUNSET("Sunset"),
    CUSTOM("Custom");

    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

data class ImportPreview(
    val newTasks: List<Task>,
    val newAspects: List<Aspect>,
    val newCategories: List<Category>,
    val existingTaskCount: Int,
    // Each entry: task title → list of "field: value" strings for unknown fields
    val unknownFieldsByTask: List<Pair<String, List<String>>> = emptyList()
)

data class CarryoverSummaryRow(
    val taskTitle: String,
    val carriedCount: Int,
    val originWeekLabel: String,
    val completionWeekLabel: String,
    val lineageMinutes: Int,
    val pointsEarned: Int,
    val isStillOpen: Boolean   // true = PENDING, false = COMPLETED
)

data class Runbook(
    val id: String,
    val name: String,
    val createdAt: String
)

data class RunbookStep(
    val id: String,
    val runbookId: String,
    val label: String,
    val stepOrder: Int
)

data class RunbookWithSteps(
    val runbook: Runbook,
    val steps: List<RunbookStep>
)

data class Subtask(
    val id: String,
    val taskId: String,
    val runbookId: String?,
    val label: String,
    val stepOrder: Int,
    val isChecked: Boolean = false
)

data class Template(
    val id: String,
    val name: String,
    val createdAt: String
)

data class TemplateTask(
    val id: String,
    val templateId: String,
    val title: String,
    val aspectName: String? = null,
    val categoryName: String? = null,
    val priority: String = "medium",
    val estimatedMinutes: Int? = null,
    val runbookId: String? = null,
    val taskOrder: Int = 0
)

data class TemplateWithTasks(
    val template: Template,
    val tasks: List<TemplateTask>
)

enum class FoodSource {
    UsdaFoundation, UsdaBranded, Custom, Remembered;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: Custom
    }
}

data class FoodItem(
    val id: String,
    val name: String,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: String,
    val servingSizeGrams: Double?,
    val calories: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val fiberG: Double? = null,
    val sodiumMg: Double? = null,
    val source: FoodSource = FoodSource.Custom,
    val fdcId: Long? = null,
    val createdAt: String
)

// Strict gram/serving math for now — see NutritionCalculator. Friendlier units (cups, tbsp)
// are a later step once strict entry has proven itself.
enum class IngredientUnit {
    GRAM, SERVING;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: SERVING
    }
}

data class Recipe(
    val id: String,
    val name: String,
    val servings: Double = 1.0,
    val createdAt: String
)

data class RecipeIngredient(
    val id: String,
    val recipeId: String,
    val foodItemId: String,
    val quantity: Double,
    val unit: IngredientUnit,
    val sortOrder: Int = 0
)

data class NutritionTotals(
    val calories: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double
) {
    operator fun plus(other: NutritionTotals) = NutritionTotals(
        calories + other.calories,
        carbsG + other.carbsG,
        proteinG + other.proteinG,
        fatG + other.fatG
    )

    operator fun div(divisor: Double) = NutritionTotals(
        calories / divisor,
        carbsG / divisor,
        proteinG / divisor,
        fatG / divisor
    )

    operator fun times(factor: Double) = NutritionTotals(
        calories * factor,
        carbsG * factor,
        proteinG * factor,
        fatG * factor
    )

    companion object {
        val ZERO = NutritionTotals(0.0, 0.0, 0.0, 0.0)
    }
}

data class RecipeNutrition(
    val recipe: Recipe,
    val total: NutritionTotals,
    val perServing: NutritionTotals
)

// How a FoodLogEntry came to exist — distinct from FoodSource (provenance of a FoodItem).
enum class FoodLogSource {
    PLANNED, ADJUSTED, AD_HOC;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: AD_HOC
    }
}

data class FoodLogEntry(
    val id: String,
    val foodItemId: String?,
    val name: String,
    val quantity: Double,
    val unit: IngredientUnit,
    val calories: Double,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val loggedAt: String,
    val source: FoodLogSource = FoodLogSource.AD_HOC,
    val confirmed: Boolean = false,
    val confirmedAt: String? = null,
    val weeklyMenuItemId: String? = null
)

data class WeeklyMenuItem(
    val id: String,
    val weekStartDate: String,
    val recipeId: String? = null,
    val mealName: String,
    val plannedServings: Double = 1.0,
    val assignedDate: String? = null,
    val mealType: String? = null,
    val createdAt: String
)
