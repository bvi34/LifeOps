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
    val projectId: String? = null,
    val source: TaskSource = TaskSource.MANUAL,
    val slug: String = "",
    val carryForwardReason: CarryForwardReason? = null,
    val counterId: String? = null,
    // See TaskEntity: week-interval cadence (recurrenceDayOfMonth == null) or monthly-by-date.
    val recurrenceIntervalWeeks: Int = 1,
    val recurrenceDayOfMonth: Int? = null
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

/** One finished run's scoreboard record (DESIGN.md §6). See GameScoreEntity for field meanings. */
data class GameScore(
    val id: String,
    val weekKey: String,
    val pointInvestment: Int,
    val score: Long,
    val setReached: Int,
    val waveReached: Int,
    val totalWaves: Int,
    val levelReached: Int,
    val weapon: String,
    val challengeMode: String,
    val createdAt: String,
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
    val completedAt: String? = null,
    val sourceFutureProjectId: String? = null
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
    val createdAt: String,
    val isHabit: Boolean = false,
    val reminderHour: Int? = null
)

/**
 * The weather conditions captured alongside a [CounterEvent] at the moment it was logged. Present
 * only on live ticks that had fresh cached conditions to draw on; every field is nullable so a
 * partially-known reading (e.g. no humidity from the NWS forecast product) still records what it
 * can. [locationName] names the tracked place the reading came from and [observedAt] is that
 * snapshot's observation time, so the UI can convey how current the numbers were.
 */
data class CounterEventWeather(
    val temperatureF: Int? = null,
    val feelsLikeF: Int? = null,
    val humidityPct: Int? = null,
    val windMph: Int? = null,
    val conditions: String? = null,
    val locationName: String? = null,
    val observedAt: String? = null
) {
    /** True when nothing meaningful was captured — the mapper treats this as "no weather". */
    val isEmpty: Boolean
        get() = temperatureF == null && feelsLikeF == null && humidityPct == null &&
            windMph == null && conditions.isNullOrBlank() && locationName.isNullOrBlank()
}

data class CounterEvent(
    val id: String,
    val counterId: String,
    val weekKey: Int,
    val occurredAt: String,
    val delta: Int = 1,
    val note: String? = null,
    // Weather at the moment of the tick, or null when none was captured (backdated entry, no
    // tracked location, or an empty/stale cache). See [CounterEventWeather].
    val weather: CounterEventWeather? = null
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

enum class BookStatus {
    TO_READ, READING, DONE;
    companion object {
        fun from(value: String) = entries.firstOrNull { it.name == value } ?: TO_READ
    }
}

data class Book(
    val id: String,
    /** The user-editable GUI title. Sync seeds it once (from [citationTitle]) then never touches it. */
    val title: String,
    val author: String? = null,
    val status: BookStatus = BookStatus.TO_READ,
    val createdAt: String,
    val completedAt: String? = null,
    /** Source kind from Citation (EPUB/PDF/ROYAL_ROAD/OREILLY); null for LifeOps-created books. */
    val sourceType: String? = null,
    /** Derived reading category (LEARNING/FUN) for the reading report; null when unknown. */
    val category: String? = null,
    /** Citation's source id (O'Reilly product id / ISBN / Royal Road id); null when unknown. */
    val sourceId: String? = null,
    /** The title Citation reports — the immutable Citation record, shown alongside the GUI [title]. */
    val citationTitle: String? = null
)

data class BookNote(
    val id: String,
    val bookId: String,
    val content: String,
    val createdAt: String
)

data class BookTimeEntry(
    val id: String,
    val bookId: String,
    val durationMinutes: Int,
    val note: String? = null,
    val recordedAt: String
)

enum class FutureProjectStatus(val value: String) {
    ACTIVE("active"),
    ARCHIVED("archived");
    companion object { fun from(value: String) = entries.firstOrNull { it.value == value } ?: ACTIVE }
}

data class FutureProject(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val status: FutureProjectStatus = FutureProjectStatus.ACTIVE
)

data class FutureProjectNote(
    val id: String,
    val projectId: String,
    val content: String,
    val createdAt: String
)

/** How much sun exposure a person tolerates — feeds the roadmap's Phase 4 outdoor scoring. */
enum class SunSensitivity(val value: String, val label: String) {
    LOW("low", "Low"),
    MODERATE("moderate", "Moderate"),
    HIGH("high", "High");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: MODERATE
    }
}

/**
 * Where a person sits in the household/relationship graph — the grouping RelationshipAnalytics
 * uses to spot imbalance (e.g. one child getting far less 1:1 time than a sibling). Unset (null
 * on Person) means "don't include this person in relationship analytics" — colleagues or
 * loosely-tracked contacts you don't want nudges about.
 */
enum class Relationship(val value: String, val label: String) {
    SPOUSE("spouse", "Spouse/Partner"),
    CHILD("child", "Child"),
    PARENT("parent", "Parent"),
    SIBLING("sibling", "Sibling"),
    EXTENDED_FAMILY("extended_family", "Extended Family"),
    FRIEND("friend", "Friend"),
    COLLEAGUE("colleague", "Colleague"),
    OTHER("other", "Other");

    companion object {
        fun from(value: String?): Relationship? = entries.firstOrNull { it.value == value }
    }
}

/**
 * A household member. Weather-comfort preferences are all nullable ("no opinion" = never rules a
 * time out), so a person can be as simple as a name or as detailed as a full comfort profile.
 * Timeline notes are separate ([PersonNote]); task involvement is a many-to-many join.
 */
data class Person(
    val id: String,
    val name: String,
    val heatToleranceMaxF: Int? = null,
    val coldToleranceMinF: Int? = null,
    val uvMax: Int? = null,
    val windMaxMph: Int? = null,
    val maxPrecipitationPct: Int? = null,
    val sunSensitivity: SunSensitivity = SunSensitivity.MODERATE,
    val activityPreferences: String? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: String,
    // Null = not tracked in relationship-balance analytics (see RelationshipAnalytics).
    val relationship: Relationship? = null,
    // Contact identity: how GoogleCalendarSyncRepository recognizes this person on a pulled-in
    // event's attendee list (matched case-insensitively) and where it sends a pushed event's
    // attendee row. Phone is informational for now — Android calendar events don't carry phone
    // numbers, but it's here for whichever future contact-matching source needs it.
    val email: String? = null,
    val phone: String? = null
)

data class PersonNote(
    val id: String,
    val personId: String,
    val content: String,
    val createdAt: String
)

/**
 * A rare, once-in-a-lifetime accomplishment. Recorded after the fact and granted its [points]
 * immediately (into the attached aspect's mapped resources), not at week-close. Optionally attached
 * to an [aspectId] and/or a [personId]; both are cleared (not deleted) if the aspect/person is
 * removed, mirroring the entity's ON DELETE SET NULL.
 */
data class Milestone(
    val id: String,
    val title: String,
    val description: String? = null,
    val points: Int = 0,
    val aspectId: String? = null,
    val personId: String? = null,
    val achievedAt: String,
    val createdAt: String
)

/**
 * Optional weather constraints attached to a task (Phase 3). A task with [outdoorPreferred] set
 * opts into "best time" recommendations; the nullable ceilings/floors are hard limits the engine
 * uses to disqualify unsuitable forecast windows. All-null means "no weather opinion" and the
 * row simply won't exist for most tasks.
 */
data class TaskWeatherRequirement(
    val taskId: String,
    val outdoorPreferred: Boolean = false,
    val durationMinutes: Int? = null,
    val maxTempF: Int? = null,
    val minTempF: Int? = null,
    val avoidRain: Boolean = false,
    val maxWindMph: Int? = null
) {
    /** True when nothing meaningful is set — the caller can delete the row instead of storing it. */
    val isEmpty: Boolean
        get() = !outdoorPreferred && durationMinutes == null && maxTempF == null &&
            minTempF == null && !avoidRain && maxWindMph == null
}

/**
 * A reusable "saved activity" (Phase 4) — Mowing, Car Washing, or anything the user builds — that
 * carries a default set of weather requirements. Applying one stamps its constraints onto a task.
 * Built-ins are seeded on first launch but are fully editable/deletable; [isBuiltIn] only records
 * provenance so seeding runs once. Custom templates are just rows with [isBuiltIn] = false.
 */
data class ActivityTemplate(
    val id: String,
    val name: String,
    val outdoorPreferred: Boolean = true,
    val durationMinutes: Int? = null,
    val maxTempF: Int? = null,
    val minTempF: Int? = null,
    val avoidRain: Boolean = false,
    val maxWindMph: Int? = null,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: String
) {
    /** Project this template's defaults onto [taskId] as a task weather requirement. */
    fun toRequirement(taskId: String) = TaskWeatherRequirement(
        taskId = taskId,
        outdoorPreferred = outdoorPreferred,
        durationMinutes = durationMinutes,
        maxTempF = maxTempF,
        minTempF = minTempF,
        avoidRain = avoidRain,
        maxWindMph = maxWindMph
    )
}

/**
 * A recorded manual override (Phase 5 learning): when a user applies an activity template to a task
 * but then changes one of its numeric limits before saving, the delta is logged here. Enough of
 * these trending the same way lets the app suggest adjusting the activity's default (see
 * PreferenceLearning). [field] is a stable key like "maxTempF" / "minTempF" / "maxWindMph" /
 * "durationMinutes".
 */
data class ActivityOverride(
    val id: String,
    val activityId: String,
    val field: String,
    val templateValue: Int?,
    val userValue: Int?,
    val createdAt: String
)

/** Whether a wellness data point is a daytime check-in or a morning sleep report. */
enum class WellnessKind(val value: String) {
    CHECKIN("CHECKIN"),
    SLEEP("SLEEP");
    companion object { fun from(value: String?) = entries.firstOrNull { it.value == value } ?: CHECKIN }
}

/**
 * How a check-in compares with the reading before it — the relative answer that replaced
 * re-scoring energy and sensory load from scratch at every prompt. [step] is how far it moves the
 * previous energy reading on the 1–10 scale when no exact rating was given
 * (WellnessRepository.logCheckin).
 */
enum class WellnessTrend(val value: String, val step: Int, val label: String) {
    BETTER("BETTER", 1, "Better"),
    SAME("SAME", 0, "Same"),
    WORSE("WORSE", -1, "Worse");

    /**
     * Where this answer lands on the 1–10 energy scale, measured against [previousEnergy]: one step
     * up, level, or one step down, clamped to the scale. With nothing to measure against — a fresh
     * install, or the first check-in ever — [NEUTRAL_ENERGY] stands in for the middle of the scale.
     */
    fun energyFrom(previousEnergy: Int?): Int =
        ((previousEnergy ?: NEUTRAL_ENERGY) + step).coerceIn(1, 10)

    companion object {
        /** Middle of the 1–10 scale: the anchor when there is no previous reading at all. */
        const val NEUTRAL_ENERGY = 5

        fun from(value: String?) = entries.firstOrNull { it.value == value }
    }
}

/**
 * Desire to do things at the moment of a check-in. [score] maps it to -1..+1 so runs of check-ins
 * average into a single "leaning yes / mixed / leaning no" number for the reports.
 */
enum class Initiative(val value: String, val score: Int, val label: String) {
    YES("YES", 1, "Yes"),
    NEUTRAL("NEUTRAL", 0, "Neutral"),
    NO("NO", -1, "No");
    companion object { fun from(value: String?) = entries.firstOrNull { it.value == value } }
}

/**
 * Domain view of a WellnessCheckinEntity. See the entity KDoc for how the two [kind]s share one
 * shape. For CHECKIN rows: [trend] and [initiative] are what the user actually answered, and
 * [energy] is derived from [trend] ([energyDerived] true) unless they opened the optional exact
 * ratings. For SLEEP rows only: [sleepMinutes] is the reconstructed total sleep, and the
 * [sleepBedtime]/[sleepWakeTime]/[sleepInterruptions]/[longestSleepMinutes] fields hold the rest of
 * the overnight reconstruction (see [com.lifeops.app.util.SleepInferenceService]) when the raw
 * phone-activity events supported one; they stay null on hand-entered or estimate-only reports.
 */
data class WellnessCheckin(
    val id: String,
    val kind: WellnessKind,
    val recordedAt: String,
    val weekKey: Int,
    val dayKey: String,
    val energy: Int? = null,
    val sensory: Int? = null,
    val trend: WellnessTrend? = null,
    val initiative: Initiative? = null,
    val energyDerived: Boolean = false,
    val tired: Int? = null,
    val sleepMinutes: Int? = null,
    val note: String? = null,
    val sleepBedtime: String? = null,
    val sleepWakeTime: String? = null,
    val sleepInterruptions: Int? = null,
    val longestSleepMinutes: Int? = null
)

/** The raw device signals sleep reconstruction is built from. Stored as [value] in phone_activity_events.type. */
enum class PhoneActivityType(val value: String) {
    SCREEN_ON("SCREEN_ON"),
    SCREEN_OFF("SCREEN_OFF"),
    CHARGING_START("CHARGING_START"),
    CHARGING_STOP("CHARGING_STOP");

    companion object {
        fun from(value: String?): PhoneActivityType? = entries.firstOrNull { it.value == value }
    }
}

/** Domain view of a PhoneActivityEventEntity: one timestamped device signal. */
data class PhoneActivityEvent(
    val id: String,
    val type: PhoneActivityType,
    val occurredAt: Long,
    val dayKey: String
)
