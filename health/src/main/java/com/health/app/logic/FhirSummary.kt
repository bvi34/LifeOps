package com.health.app.logic

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A line and a date for a FHIR resource — a vaccination, a lab result, a condition — as it arrives
 * in Health Connect's medical records.
 *
 * The resource itself is kept whole; this only decides what a list shows for it. FHIR names things
 * in a dozen places depending on the resource type (`vaccineCode`, `medicationCodeableConcept`,
 * `code`), and dates in a dozen more (`occurrenceDateTime`, `effectiveDateTime`, `onsetDateTime`,
 * `recordedDate`), so both are looked for in order and the first one present wins.
 */
data class FhirSummary(val resourceType: String?, val title: String?, val date: Long?)

object FhirSummaries {

    private val TITLE_FIELDS = listOf(
        "vaccineCode", "medicationCodeableConcept", "code", "type", "class", "serviceType"
    )

    private val DATE_FIELDS = listOf(
        "occurrenceDateTime", "effectiveDateTime", "onsetDateTime", "performedDateTime",
        "recordedDate", "authoredOn", "issued", "date", "birthDate"
    )

    private val PERIOD_FIELDS = listOf("effectivePeriod", "performedPeriod", "period", "onsetPeriod")

    fun of(json: String): FhirSummary {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return FhirSummary(null, null, null)
        return FhirSummary(
            resourceType = root.string("resourceType"),
            title = title(root),
            date = date(root)
        )
    }

    private fun title(root: JsonObject): String? {
        for (field in TITLE_FIELDS) {
            val element = root.get(field) ?: continue
            // `class` on an Encounter is a single Coding, `type` a list of CodeableConcepts.
            val concept = if (element.isJsonArray) element.asJsonArray.firstOrNull() else element
            concept?.let { conceptText(it) }?.let { return it }
        }
        // A Patient or Practitioner has a name rather than a code.
        root.get("name")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.asObjectOrNull()?.let { name ->
            name.string("text")?.let { return it }
            val given = name.get("given")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.asStringOrNull() }.orEmpty()
            val parts = given + listOfNotNull(name.string("family"))
            if (parts.isNotEmpty()) return parts.joinToString(" ")
        }
        return null
    }

    private fun conceptText(element: JsonElement): String? {
        val concept = element.asObjectOrNull() ?: return null
        concept.string("text")?.let { return it }
        concept.string("display")?.let { return it }
        return concept.get("coding")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.asObjectOrNull()?.let { coding -> coding.string("display") ?: coding.string("code") } }
            ?.firstOrNull()
    }

    private fun date(root: JsonObject): Long? {
        DATE_FIELDS.firstNotNullOfOrNull { root.string(it)?.let(::parseDate) }?.let { return it }
        return PERIOD_FIELDS.firstNotNullOfOrNull { field ->
            root.get(field)?.asObjectOrNull()?.string("start")?.let(::parseDate)
        }
    }

    /**
     * A FHIR date: a full timestamp with an offset, a bare instant, or a date alone — `2024`,
     * `2024-03`, `2024-03-14` — read as the start of that year, month or day in UTC.
     */
    fun parseDate(text: String): Long? {
        val trimmed = text.trim()
        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        val padded = when (trimmed.length) {
            4 -> "$trimmed-01-01"
            7 -> "$trimmed-01"
            else -> trimmed
        }
        return runCatching { LocalDate.parse(padded).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    private fun JsonObject.string(field: String): String? =
        get(field)?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
}
