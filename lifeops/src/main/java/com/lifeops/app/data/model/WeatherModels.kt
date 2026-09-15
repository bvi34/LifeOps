package com.lifeops.app.data.model



/**
 * Work that depends on the weather: what a task needs, and the activity templates that
 * decide when that need is met.
 */

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
