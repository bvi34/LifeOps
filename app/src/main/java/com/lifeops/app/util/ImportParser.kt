package com.lifeops.app.util

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lifeops.app.data.model.*
import kotlin.math.roundToInt

data class ImportResult(
    val tasks: List<ParsedTask>,
    val error: String? = null
)

data class ParsedTask(
    val title: String,
    val notes: List<String> = emptyList(),
    val aspectName: String?,
    val categoryName: String?,
    val priority: String,
    val dueDate: String?,
    val hardDeadline: Boolean,
    val status: String = "pending",
    val timeLoggedMinutes: Int? = null,
    val estimatedMinutes: Int? = null,
    val isRecurring: Boolean = false,
    val unknownFields: Map<String, String> = emptyMap()
)

object ImportParser {
    private val gson = Gson()

    private val knownKeys = setOf(
        "title", "notes", "aspect", "category", "priority",
        "due_date", "hard_deadline", "status",
        "time_logged_minutes", "estimated_minutes", "is_recurring"
    )

    fun parse(json: String): ImportResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val tasksArray = when {
                root.has("tasks") -> root.getAsJsonArray("tasks")
                else -> JsonParser.parseString(json).asJsonArray
            }
            val tasks = tasksArray.map { elem ->
                val obj = elem.asJsonObject
                val unknown = obj.entrySet()
                    .filter { it.key !in knownKeys }
                    .associate { (k, v) ->
                        k to when {
                            v.isJsonNull -> "null"
                            v.isJsonPrimitive -> v.asString
                            else -> v.toString()
                        }
                    }
                ParsedTask(
                    title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString
                        ?: return ImportResult(emptyList(), "Missing title"),
                    notes = obj.get("notes")?.takeIf { !it.isJsonNull }?.let { el ->
                        when {
                            el.isJsonArray -> el.asJsonArray.map { it.asString }
                            el.isJsonPrimitive -> listOf(el.asString)
                            else -> emptyList()
                        }
                    } ?: emptyList(),
                    aspectName = obj.get("aspect")?.takeIf { !it.isJsonNull }?.asString,
                    categoryName = obj.get("category")?.takeIf { !it.isJsonNull }?.asString,
                    priority = obj.get("priority")?.takeIf { !it.isJsonNull }?.asString ?: "medium",
                    dueDate = obj.get("due_date")?.takeIf { !it.isJsonNull }?.asString,
                    hardDeadline = obj.get("hard_deadline")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    status = obj.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "pending",
                    timeLoggedMinutes = obj.get("time_logged_minutes")?.takeIf { !it.isJsonNull }?.asInt,
                    estimatedMinutes = obj.get("estimated_minutes")?.takeIf { !it.isJsonNull }?.asInt,
                    isRecurring = obj.get("is_recurring")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    unknownFields = unknown
                )
            }
            ImportResult(tasks)
        } catch (e: Exception) {
            ImportResult(emptyList(), e.message ?: "Invalid JSON")
        }
    }

    /**
     * Points formula:
     *   Base (by estimated time):
     *     ≤ 60 min  → 1 pt per 6-min block  (60 min = 10 pts baseline)
     *     > 60 min  → 10 pts + 1 pt per additional hour
     *   Urgency multiplier:  Low 0.75x · Medium 1.0x · High 1.25x · Critical 1.5x
     *   Hard deadline:       +0.25x on urgency multiplier
     *   Manually added:      ×0.5  (ad-hoc task, no prior planning)
     * Minimum result: 1 pt.
     */
    fun computeResourceValue(
        priority: String,
        hardDeadline: Boolean,
        estimatedMinutes: Int?,
        isManuallyAdded: Boolean = false
    ): Int {
        val minutes = (estimatedMinutes ?: 60).coerceAtLeast(1)

        val baseScore = if (minutes <= 60) {
            (minutes / 6.0).roundToInt().coerceAtLeast(1)
        } else {
            10 + ((minutes - 60) / 60.0).roundToInt()
        }

        val urgencyMultiplier = when (Priority.from(priority)) {
            Priority.LOW -> 0.75
            Priority.MEDIUM -> 1.0
            Priority.HIGH -> 1.25
            Priority.CRITICAL -> 1.5
        }

        val totalMultiplier = urgencyMultiplier + (if (hardDeadline) 0.25 else 0.0)
        val manualMultiplier = if (isManuallyAdded) 0.5 else 1.0

        return (baseScore * totalMultiplier * manualMultiplier).roundToInt().coerceAtLeast(1)
    }
}
