package com.health.app.ui.connect

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.health.app.data.model.ConnectRecord
import com.health.app.logic.ConnectFormat
import com.health.app.logic.ConnectKind

/**
 * The words a list row shows from an imported record's detail: the name of a workout or a meal, a
 * lab result's title, how a night's sleep divided up. The detail itself is kept whole; this only
 * chooses what of it is worth a line.
 */
object ConnectDetail {

    /** A name that says what the record was, where it has one, shown in place of the number. */
    fun headline(record: ConnectRecord): String? {
        val detail = parse(record.detail) ?: return null
        return when (record.kind) {
            ConnectKind.EXERCISE, ConnectKind.PLANNED_EXERCISE ->
                detail.text("title") ?: detail.text("exerciseType")
            ConnectKind.NUTRITION -> detail.text("name") ?: detail.text("meal")?.replaceFirstChar { it.uppercase() }
            ConnectKind.MINDFULNESS -> detail.text("title") ?: detail.text("type")?.replaceFirstChar { it.uppercase() }
            else -> if (record.kind.isMedical) detail.text("title") ?: detail.text("resourceType") else null
        }
    }

    /** The facts that qualify the number: where it was measured, what the test said, the stages. */
    fun facts(record: ConnectRecord): String? {
        val detail = parse(record.detail) ?: return null
        val parts = when (record.kind) {
            ConnectKind.SLEEP -> listOfNotNull(sleepStages(detail))
            ConnectKind.HEART_RATE, ConnectKind.SPEED, ConnectKind.POWER,
            ConnectKind.STEPS_CADENCE, ConnectKind.CYCLING_CADENCE -> listOfNotNull(
                range(detail, record.kind)
            )
            ConnectKind.BLOOD_PRESSURE -> listOfNotNull(detail.known("bodyPosition"), detail.known("location"))
            ConnectKind.BLOOD_GLUCOSE -> listOfNotNull(detail.known("relationToMeal"), detail.known("specimen"))
            ConnectKind.BODY_TEMPERATURE, ConnectKind.BASAL_BODY_TEMPERATURE, ConnectKind.SKIN_TEMPERATURE ->
                listOfNotNull(detail.known("location"))
            ConnectKind.MENSTRUATION_FLOW -> listOfNotNull(detail.known("flow"))
            ConnectKind.OVULATION_TEST -> listOfNotNull(detail.known("result"))
            ConnectKind.CERVICAL_MUCUS -> listOfNotNull(detail.known("appearance"), detail.known("sensation"))
            ConnectKind.SEXUAL_ACTIVITY -> listOfNotNull(detail.known("protection"))
            ConnectKind.VO2_MAX -> listOfNotNull(detail.known("method"))
            ConnectKind.EXERCISE -> listOfNotNull(detail.text("exerciseType").takeIf { detail.text("title") != null })
            else -> emptyList()
        }
        return parts.joinToString(" · ").ifBlank { null }
    }

    private fun sleepStages(detail: JsonObject): String? {
        val stages = detail.get("stages")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val minutes = mutableMapOf<String, Double>()
        stages.forEach { element ->
            val stage = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val name = stage.text("stage") ?: return@forEach
            val start = stage.get("start")?.asDouble ?: return@forEach
            val end = stage.get("end")?.asDouble ?: return@forEach
            minutes[name] = (minutes[name] ?: 0.0) + (end - start) / 60_000
        }
        if (minutes.isEmpty()) return null
        return listOf("deep", "light", "REM", "sleeping", "awake", "awake in bed", "out of bed")
            .mapNotNull { name -> minutes[name]?.let { "$name ${ConnectFormat.duration(it * 60_000)}" } }
            .joinToString(", ")
    }

    private fun range(detail: JsonObject, kind: ConnectKind): String? {
        val min = detail.get("min")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
        val max = detail.get("max")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
        val samples = detail.get("samples")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
        return "${ConnectFormat.value(kind, min, null)} – ${ConnectFormat.value(kind, max, null)}" +
            if (samples > 0) " · $samples samples" else ""
    }

    private fun parse(json: String?): JsonObject? =
        json?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
            ?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.text(field: String): String? =
        get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.ifBlank { null }

    /** A labelled enumeration, left out when the writer didn't say. */
    private fun JsonObject.known(field: String): String? = text(field)?.takeIf { it != "unknown" }
}
