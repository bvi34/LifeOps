package com.lifeops.app.util

import com.lifeops.app.data.model.ActivityOverride
import com.lifeops.app.data.model.ActivityTemplate

/**
 * Phase 5 preference learning — pure and JVM-testable. Looks at the manual overrides a user has
 * made to activity defaults and, when a field has been changed the same way enough times, suggests
 * updating that activity's default (e.g. "You keep mowing above the recommended temperature —
 * raise the threshold?"). Deliberately conservative: it needs a few consistent observations before
 * saying anything, and never suggests a value equal to the current default.
 */
data class PreferenceSuggestion(
    val activityId: String,
    val activityName: String,
    val field: String,
    val currentValue: Int?,
    val suggestedValue: Int,
    val message: String
)

object PreferenceLearning {

    /** Minimum consistent overrides before a suggestion is offered. */
    const val MIN_OBSERVATIONS = 3

    fun suggest(overrides: List<ActivityOverride>, templates: List<ActivityTemplate>): List<PreferenceSuggestion> {
        val byId = templates.associateBy { it.id }
        return overrides
            .groupBy { it.activityId to it.field }
            .mapNotNull { (key, group) ->
                val (activityId, field) = key
                val template = byId[activityId] ?: return@mapNotNull null
                val userValues = group.mapNotNull { it.userValue }
                if (userValues.size < MIN_OBSERVATIONS) return@mapNotNull null
                if (!trendsConsistently(field, group)) return@mapNotNull null

                val current = currentValue(template, field)
                val suggested = median(userValues)
                if (suggested == current) return@mapNotNull null

                PreferenceSuggestion(
                    activityId = activityId,
                    activityName = template.name,
                    field = field,
                    currentValue = current,
                    suggestedValue = suggested,
                    message = message(template.name, field, current, suggested)
                )
            }
            .sortedBy { it.activityName }
    }

    /** Human label for a field key. */
    fun fieldLabel(field: String): String = when (field) {
        "maxTempF" -> "max temperature"
        "minTempF" -> "min temperature"
        "maxWindMph" -> "max wind"
        "durationMinutes" -> "duration"
        else -> field
    }

    /** At least two-thirds of the overrides push the same direction the field cares about. */
    private fun trendsConsistently(field: String, group: List<ActivityOverride>): Boolean {
        if (field == "durationMinutes") return true // no natural direction; just track the median
        val threshold = group.size * 2 / 3
        return if (field.startsWith("max")) {
            group.count { (it.userValue ?: return@count false) > (it.templateValue ?: Int.MIN_VALUE) } >= threshold
        } else {
            group.count { (it.userValue ?: return@count false) < (it.templateValue ?: Int.MAX_VALUE) } >= threshold
        }
    }

    private fun currentValue(t: ActivityTemplate, field: String): Int? = when (field) {
        "maxTempF" -> t.maxTempF
        "minTempF" -> t.minTempF
        "maxWindMph" -> t.maxWindMph
        "durationMinutes" -> t.durationMinutes
        else -> null
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun message(name: String, field: String, current: Int?, suggested: Int): String {
        val currentText = current?.toString() ?: "none"
        return "You keep setting $name ${fieldLabel(field)} to ~$suggested (default $currentText). Update the default?"
    }
}
