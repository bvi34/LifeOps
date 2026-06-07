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
    val hardDeadline: Boolean
)

object ImportParser {
    private val gson = Gson()

    fun parse(json: String): ImportResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val tasksArray = when {
                root.has("tasks") -> root.getAsJsonArray("tasks")
                else -> JsonParser.parseString(json).asJsonArray
            }
            val tasks = tasksArray.map { elem ->
                val obj = elem.asJsonObject
                ParsedTask(
                    title = obj.get("title")?.asString ?: return ImportResult(emptyList(), "Missing title"),
                    notes = obj.get("notes")?.asString,
                    aspectName = obj.get("aspect")?.asString,
                    categoryName = obj.get("category")?.asString,
                    priority = obj.get("priority")?.asString ?: "medium",
                    dueDate = obj.get("due_date")?.asString,
                    hardDeadline = obj.get("hard_deadline")?.asBoolean ?: false
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
