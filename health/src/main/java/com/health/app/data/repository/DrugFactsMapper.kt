package com.health.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.health.app.data.db.entities.DrugFactsEntity
import com.health.app.logic.DrugMonograph
import com.health.app.logic.LabelSection

/**
 * Between the cached row and the monograph the screens read.
 *
 * Two encodings, both chosen to keep the table readable rather than clever:
 *
 *  - **Short lists are comma-joined text.** Routes and ingredients are two or three items that are
 *    only ever shown as a sentence; a join-table for them would be three files of machinery to say
 *    "oral". Items containing a comma are rare enough — and harmless enough when split — that the
 *    simple encoding wins.
 *  - **Label sections are JSON.** They are long, ordered, titled, and unbounded in number, which is
 *    exactly where the comma trick stops being honest. A malformed blob deserialises to an empty
 *    list, so a cache written by an older version can never take the screen down; the worst case is
 *    a monograph that has to be looked up again.
 */
object DrugFactsMapper {

    private val gson = Gson()

    /**
     * Sections are read back through a nullable-fielded shape rather than straight into
     * [LabelSection]. Gson fills a missing field with null regardless of what Kotlin declared, so
     * deserialising a truncated blob directly would hand the screens a `LabelSection` whose non-null
     * `text` is null — a crash at the first `isNotBlank()`, in the one code path whose whole job is
     * to survive a bad cache.
     */
    private val sectionListType = object : TypeToken<List<SectionDto>>() {}.type

    private data class SectionDto(val key: String?, val title: String?, val text: String?)

    fun toEntity(monograph: DrugMonograph, rxcui: String): DrugFactsEntity = DrugFactsEntity(
        rxcui = rxcui,
        name = monograph.name,
        genericName = monograph.genericName,
        brandName = monograph.brandName,
        doseForm = monograph.doseForm,
        routes = join(monograph.routes),
        ingredients = join(monograph.ingredients),
        availableStrengths = join(monograph.availableStrengths),
        schedule = monograph.schedule,
        productType = monograph.productType,
        manufacturer = monograph.manufacturer,
        labelSetId = monograph.labelSetId,
        labelEffectiveTime = monograph.labelEffectiveTime,
        sectionsJson = monograph.sections.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) },
        sources = join(monograph.sources),
        fetchedAt = monograph.fetchedAt
    )

    fun toMonograph(entity: DrugFactsEntity): DrugMonograph = DrugMonograph(
        rxcui = entity.rxcui,
        name = entity.name,
        genericName = entity.genericName,
        brandName = entity.brandName,
        doseForm = entity.doseForm,
        routes = split(entity.routes),
        ingredients = split(entity.ingredients),
        availableStrengths = split(entity.availableStrengths),
        schedule = entity.schedule,
        productType = entity.productType,
        manufacturer = entity.manufacturer,
        labelSetId = entity.labelSetId,
        labelEffectiveTime = entity.labelEffectiveTime,
        sections = readSections(entity.sectionsJson),
        sources = split(entity.sources),
        fetchedAt = entity.fetchedAt
    )

    private fun join(values: List<String>): String? =
        values.mapNotNull { it.trim().ifBlank { null } }.joinToString(", ").ifBlank { null }

    private fun split(value: String?): List<String> =
        value.orEmpty().split(',').mapNotNull { it.trim().ifBlank { null } }

    private fun readSections(json: String?): List<LabelSection> {
        val text = json?.ifBlank { null } ?: return emptyList()
        return try {
            gson.fromJson<List<SectionDto>>(text, sectionListType).orEmpty()
                .mapNotNull { dto ->
                    val body = dto.text?.trim()?.ifBlank { null } ?: return@mapNotNull null
                    LabelSection(
                        key = dto.key.orEmpty(),
                        title = dto.title?.ifBlank { null } ?: dto.key.orEmpty(),
                        text = body
                    )
                }
        } catch (_: JsonSyntaxException) {
            emptyList()
        }
    }
}
