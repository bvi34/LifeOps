package com.lifeops.app.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lifeops.app.data.model.*
import java.time.Instant
import java.util.UUID

data class ImportResult(
    val tasks: List<ParsedTask>,
    val error: String? = null
)

data class ParsedTask(
    val title: String,
    val notes: String?,
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
                    title = obj.get("title")?.asString ?: return ImportResult(emptyList(), "Missing title"),
                    notes = obj.get("notes")?.asString,
                    aspectName = obj.get("aspect")?.asString,
                    categoryName = obj.get("category")?.asString,
                    priority = obj.get("priority")?.asString ?: "medium",
                    dueDate = obj.get("due_date")?.asString,
                    hardDeadline = obj.get("hard_deadline")?.asBoolean ?: false,
                    status = obj.get("status")?.asString ?: "pending",
                    timeLoggedMinutes = obj.get("time_logged_minutes")?.takeIf { !it.isJsonNull }?.asInt,
                    estimatedMinutes = obj.get("estimated_minutes")?.takeIf { !it.isJsonNull }?.asInt,
                    isRecurring = obj.get("is_recurring")?.asBoolean ?: false,
                    unknownFields = unknown
                )
            }
            ImportResult(tasks)
        } catch (e: Exception) {
            ImportResult(emptyList(), e.message ?: "Invalid JSON")
        }
    }

    fun computeResourceValue(priority: String, hardDeadline: Boolean): Int {
        val base = Priority.from(priority).baseValue
        return if (hardDeadline) base + 10 else base
    }
}
