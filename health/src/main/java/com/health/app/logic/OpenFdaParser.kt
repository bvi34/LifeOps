package com.health.app.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Pure openFDA (`api.fda.gov/drug/label.json`) response parsing — the Structured Product Label, the
 * text actually printed on the box, turned into [LabelSection]s. No network, no Android; the HTTP
 * hop lives in `data/net/OpenFdaClient`.
 *
 * Two rules run through this file, and both are about *not* being clever:
 *
 *  - **The label's words are kept, not summarised.** Sections are joined, whitespace is collapsed,
 *    and that is the entire transformation. Health is a place to read a label at 3am without going
 *    to find the box; it is not a place that decides which half of a warning matters.
 *  - **Only a fixed, ordered set of sections is kept.** A full SPL carries clinical-pharmacology and
 *    packaging fields nobody reads at a cupboard, and an unbounded blob would swamp both the screen
 *    and the database. [SECTION_ORDER] is that set, in the order the questions get asked: what is
 *    this for, how much, then everything that starts with "don't".
 *
 * A label that carries none of them parses to an empty section list rather than an error — plenty of
 * products have a sparse label, and the RxNorm half of the monograph is still worth having.
 */
object OpenFdaParser {

    private val gson = Gson()

    // The openFDA field names, as keys, so [LabelSection.key] is stable across labels and versions.
    const val PURPOSE = "purpose"
    const val INDICATIONS = "indications_and_usage"
    const val ACTIVE_INGREDIENT = "active_ingredient"
    const val DOSAGE = "dosage_and_administration"
    const val WARNINGS = "warnings"
    const val DO_NOT_USE = "do_not_use"
    const val ASK_DOCTOR = "ask_doctor"
    const val ASK_DOCTOR_OR_PHARMACIST = "ask_doctor_or_pharmacist"
    const val WHEN_USING = "when_using"
    const val STOP_USE = "stop_use"
    const val PREGNANCY = "pregnancy_or_breast_feeding"
    const val KEEP_OUT_OF_REACH = "keep_out_of_reach_of_children"
    const val DRUG_INTERACTIONS = "drug_interactions"
    const val OVERDOSAGE = "overdosage"
    const val STORAGE = "storage_and_handling"
    const val INACTIVE_INGREDIENT = "inactive_ingredient"

    /**
     * The sections Health keeps, in the order it shows them. Titles are the plain-English ones, not
     * the regulatory field names — "Do not use" is what the box says and what a reader is scanning
     * for; `do_not_use` is not.
     */
    val SECTION_ORDER: List<Pair<String, String>> = listOf(
        PURPOSE to "Purpose",
        INDICATIONS to "What it's for",
        ACTIVE_INGREDIENT to "Active ingredient",
        DOSAGE to "Directions from the label",
        WARNINGS to "Warnings",
        DO_NOT_USE to "Do not use",
        ASK_DOCTOR to "Ask a doctor before use if",
        ASK_DOCTOR_OR_PHARMACIST to "Ask a doctor or pharmacist if",
        WHEN_USING to "When using this product",
        STOP_USE to "Stop use and ask a doctor if",
        PREGNANCY to "If pregnant or breastfeeding",
        DRUG_INTERACTIONS to "Interactions",
        OVERDOSAGE to "Overdose",
        KEEP_OUT_OF_REACH to "Keep out of reach of children",
        STORAGE to "Storage",
        INACTIVE_INGREDIENT to "Inactive ingredients"
    )

    /**
     * The first label in an openFDA response, merged with whatever RxNorm already established in
     * [base]. RxNorm wins on identity (it is the vocabulary, and the name the user picked came from
     * it); openFDA wins on the fields RxNorm has no opinion about — manufacturer, product type, and
     * the label text itself.
     *
     * openFDA answers a search with an array; Health asks for one and takes the first. Several
     * manufacturers can label the same generic product, and picking the top hit is honest — the
     * monograph says which label it is, with its own effective date.
     */
    fun parseLabel(json: String, base: DrugMonograph): DrugMonograph {
        val result = read<LabelResponseDto>(json)?.results?.firstOrNull() ?: return base
        val openFda = result.openfda

        val sections = SECTION_ORDER.mapNotNull { (key, title) ->
            joinSection(result.section(key))?.let { LabelSection(key, title, it) }
        }

        return base.copy(
            genericName = base.genericName ?: openFda?.generic_name.firstNonBlank(),
            brandName = base.brandName ?: openFda?.brand_name.firstNonBlank(),
            routes = base.routes.ifEmpty { openFda?.route.orEmpty().cleaned() },
            ingredients = base.ingredients.ifEmpty { openFda?.substance_name.orEmpty().cleaned() },
            productType = openFda?.product_type.firstNonBlank() ?: base.productType,
            manufacturer = openFda?.manufacturer_name.firstNonBlank() ?: base.manufacturer,
            labelSetId = result.set_id?.trim()?.ifBlank { null } ?: base.labelSetId,
            labelEffectiveTime = result.effective_time?.trim()?.ifBlank { null } ?: base.labelEffectiveTime,
            sections = sections,
            sources = (base.sources + DrugSources.OPENFDA).distinct()
        )
    }

    /**
     * `"20190417"` → `"17 Apr 2019"`. openFDA's `effective_time` is a bare `yyyyMMdd` string, which
     * reads as a serial number rather than a date; anything that isn't one is passed through
     * untouched rather than dropped, since a date Health can't parse is still a date the label has.
     */
    fun formatEffectiveTime(raw: String?): String? {
        val text = raw?.trim()?.ifBlank { null } ?: return null
        if (text.length != 8 || text.any { !it.isDigit() }) return text
        val year = text.substring(0, 4)
        val month = text.substring(4, 6).toIntOrNull() ?: return text
        val day = text.substring(6, 8).toIntOrNull() ?: return text
        val monthName = MONTHS.getOrNull(month - 1) ?: return text
        return "$day $monthName $year"
    }

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /**
     * A section arrives as an array of paragraphs. They are joined with blank lines and their
     * internal whitespace collapsed — SPL text is full of the line breaks of the original typeset
     * page, which on a phone read as ragged nonsense. Nothing else is touched: the wording, the
     * order and the emphasis are the manufacturer's.
     */
    private fun joinSection(paragraphs: List<String>?): String? {
        val text = paragraphs.orEmpty()
            .mapNotNull { it.replace(WHITESPACE, " ").trim().ifBlank { null } }
            .joinToString("\n\n")
        return text.ifBlank { null }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun List<String>?.cleaned(): List<String> =
        orEmpty().mapNotNull { it.trim().ifBlank { null } }.distinct()

    private fun List<String>?.firstNonBlank(): String? =
        orEmpty().firstOrNull { it.isNotBlank() }?.trim()

    private inline fun <reified T> read(json: String): T? = try {
        gson.fromJson(json, T::class.java)
    } catch (_: JsonSyntaxException) {
        null
    }

    // --- wire shapes -----------------------------------------------------------------------------

    private data class LabelResponseDto(val results: List<LabelDto>?)

    /**
     * One label. Every section is an array of strings in openFDA's schema, including the ones that
     * are always a single paragraph — so they are all typed the same way and joined the same way.
     */
    private data class LabelDto(
        val set_id: String?,
        val effective_time: String?,
        val openfda: OpenFdaDto?,
        val purpose: List<String>?,
        val indications_and_usage: List<String>?,
        val active_ingredient: List<String>?,
        val dosage_and_administration: List<String>?,
        val warnings: List<String>?,
        val do_not_use: List<String>?,
        val ask_doctor: List<String>?,
        val ask_doctor_or_pharmacist: List<String>?,
        val when_using: List<String>?,
        val stop_use: List<String>?,
        val pregnancy_or_breast_feeding: List<String>?,
        val keep_out_of_reach_of_children: List<String>?,
        val drug_interactions: List<String>?,
        val overdosage: List<String>?,
        val storage_and_handling: List<String>?,
        val inactive_ingredient: List<String>?
    ) {
        fun section(key: String): List<String>? = when (key) {
            PURPOSE -> purpose
            INDICATIONS -> indications_and_usage
            ACTIVE_INGREDIENT -> active_ingredient
            DOSAGE -> dosage_and_administration
            WARNINGS -> warnings
            DO_NOT_USE -> do_not_use
            ASK_DOCTOR -> ask_doctor
            ASK_DOCTOR_OR_PHARMACIST -> ask_doctor_or_pharmacist
            WHEN_USING -> when_using
            STOP_USE -> stop_use
            PREGNANCY -> pregnancy_or_breast_feeding
            KEEP_OUT_OF_REACH -> keep_out_of_reach_of_children
            DRUG_INTERACTIONS -> drug_interactions
            OVERDOSAGE -> overdosage
            STORAGE -> storage_and_handling
            INACTIVE_INGREDIENT -> inactive_ingredient
            else -> null
        }
    }

    private data class OpenFdaDto(
        val brand_name: List<String>?,
        val generic_name: List<String>?,
        val manufacturer_name: List<String>?,
        val product_type: List<String>?,
        val route: List<String>?,
        val substance_name: List<String>?,
        val rxcui: List<String>?
    )
}
