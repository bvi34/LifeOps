package com.health.app.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Pure RxNorm (rxnav.nlm.nih.gov) response parsing — raw JSON in, [DrugCandidate]s and monograph
 * fragments out. No network and no Android, so every shape below is testable against a captured
 * payload; the HTTP hop lives in `data/net/RxNormClient`. Mirrors LifeOps' NwsClient/NwsParser split
 * for the same reason: an API's JSON is the part most likely to surprise you, and it is worth being
 * able to prove what happens to a surprising one without a device.
 *
 * Every entry point returns empty or null rather than throwing on malformed input. A lookup is a
 * convenience layered over a medicine the user could always type in by hand — it must never be able
 * to take the screen down with it.
 *
 * The four calls Health makes:
 *
 *  1. `GET /REST/drugs.json?name={term}`                      → [parseDrugs]      — exact-name search
 *  2. `GET /REST/approximateTerm.json?term={term}&maxEntries=` → [parseApproximate] — the typo path
 *  3. `GET /REST/rxcui/{rxcui}/properties.json`               → [parseProperties]  — name/term type
 *  4. `GET /REST/rxcui/{rxcui}/allProperties.json?prop=attributes` → [parseAttributes]
 *  5. `GET /REST/rxcui/{rxcui}/related.json?tty=IN+PIN+BN+DF` → [parseRelated]     — what it's made of
 */
object RxNormParser {

    private val gson = Gson()

    /** Suppressed and non-English concepts are noise in a household cupboard; they're dropped. */
    private const val SUPPRESSED = "Y"

    /**
     * The exact-name search. RxNorm groups its hits by term type, and the groups arrive in no
     * particular order, so results are re-sorted by [DrugConceptKind.rank] — the specific products
     * (which have a strength, and therefore a label openFDA can find) before the bare ingredients.
     */
    fun parseDrugs(json: String): List<DrugCandidate> {
        val dto = read<DrugsDto>(json) ?: return emptyList()
        return dto.drugGroup?.conceptGroup
            ?.flatMap { group ->
                group.conceptProperties.orEmpty().map { it.toCandidate(fallbackTty = group.tty) }
            }
            .orEmpty()
            .filterNotNull()
            .distinctBy { it.rxcui }
            .sortedWith(compareBy({ it.kind.rank }, { it.name.length }))
    }

    /**
     * The spelling-tolerant search, used only when [parseDrugs] came back empty — "childrens
     * tylonol" should still find something. RxNorm scores each candidate; ties are broken by its own
     * rank, and the same concept can be reached by several of its names, so ids are de-duplicated
     * keeping the best-scoring row.
     *
     * The candidate rows do not reliably carry a name — depending on the deployment they may be no
     * more than an id and a score — so [DrugCandidate.name] can come back blank here. The client
     * resolves those with a `properties` call rather than showing a user a bare number.
     */
    fun parseApproximate(json: String): List<DrugCandidate> {
        val dto = read<ApproximateDto>(json) ?: return emptyList()
        return dto.approximateGroup?.candidate.orEmpty()
            .mapNotNull { candidate ->
                val rxcui = candidate.rxcui?.trim().orEmpty()
                if (rxcui.isEmpty()) return@mapNotNull null
                DrugCandidate(
                    rxcui = rxcui,
                    name = candidate.name?.trim().orEmpty(),
                    tty = candidate.tty?.trim().orEmpty(),
                    score = candidate.score?.trim()?.toIntOrNull() ?: 0
                )
            }
            // Keep the best-scoring row per concept, then order by score descending.
            .groupBy { it.rxcui }
            .map { (_, rows) -> rows.maxBy { it.score } }
            .sortedWith(compareByDescending<DrugCandidate> { it.score }.thenBy { it.kind.rank })
    }

    /** One concept's own name and term type — the fill-in for a candidate that arrived without one. */
    fun parseProperties(json: String): DrugCandidate? {
        val props = read<PropertiesDto>(json)?.properties ?: return null
        return props.toCandidate(fallbackTty = props.tty)
    }

    /**
     * RxNorm's attribute bag for one concept. Only two entries earn their keep here: the strengths a
     * product is sold in, and its controlled-substance schedule when it has one.
     *
     * The schedule arrives as a bare digit (`"2"`), which means nothing to a reader, so it is
     * written out. `"0"` is RxNorm's "not scheduled" and is dropped rather than shown as a
     * reassurance Health has no business offering.
     */
    fun parseAttributes(json: String): Attributes {
        val concepts = read<AllPropertiesDto>(json)?.propConceptGroup?.propConcept.orEmpty()
        val strengths = concepts
            .filter { it.propName.equalsIgnoreCase("AVAILABLE_STRENGTH") }
            // A multi-ingredient product's strength is one value with the parts inside it
            // ("325 mg / 5 mg"), so it is kept whole; only repeats across concepts are collapsed.
            .mapNotNull { it.propValue?.trim()?.ifBlank { null } }
            .distinct()
        val schedule = concepts
            .firstOrNull { it.propName.equalsIgnoreCase("SCHEDULE") }
            ?.propValue?.trim()
            ?.let { scheduleLabel(it) }
        return Attributes(availableStrengths = strengths, schedule = schedule)
    }

    /**
     * The concepts related to a product: what it is made of (`IN`/`PIN`), what it is sold as
     * (`BN`), and the form it comes in (`DF`). This is what turns "Children's Tylenol" into
     * "acetaminophen, oral suspension" — the two facts a person actually needs when comparing the
     * bottle in their hand to the one in the cupboard.
     */
    fun parseRelated(json: String): Related {
        val groups = read<RelatedDto>(json)?.relatedGroup?.conceptGroup.orEmpty()
        fun namesOf(vararg ttys: String): List<String> = groups
            .filter { group -> group.tty?.uppercase() in ttys.toSet() }
            .flatMap { it.conceptProperties.orEmpty() }
            .mapNotNull { it.name?.trim()?.ifBlank { null } }
            .distinct()

        // Prefer precise ingredients when RxNorm has them ("ibuprofen lysine" over "ibuprofen"),
        // because that distinction is occasionally the whole point of the product.
        val precise = namesOf("PIN")
        return Related(
            ingredients = precise.ifEmpty { namesOf("IN", "MIN") },
            brandNames = namesOf("BN"),
            doseForms = namesOf("DF", "DFG")
        )
    }

    /** What [parseAttributes] found worth keeping. */
    data class Attributes(
        val availableStrengths: List<String> = emptyList(),
        val schedule: String? = null
    )

    /** What [parseRelated] found worth keeping. */
    data class Related(
        val ingredients: List<String> = emptyList(),
        val brandNames: List<String> = emptyList(),
        val doseForms: List<String> = emptyList()
    )

    /** `"2"` → `"Schedule II (controlled)"`; `"C-II"` is passed through; `"0"` means nothing. */
    private fun scheduleLabel(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val roman = when (digits) {
            "1" -> "I"
            "2" -> "II"
            "3" -> "III"
            "4" -> "IV"
            "5" -> "V"
            else -> null
        } ?: return raw.takeIf { it.isNotBlank() && digits != "0" }
        return "Schedule $roman (controlled substance)"
    }

    private fun ConceptDto.toCandidate(fallbackTty: String?): DrugCandidate? {
        val id = rxcui?.trim().orEmpty()
        val label = name?.trim().orEmpty()
        if (id.isEmpty() || label.isEmpty()) return null
        if (suppress?.equalsIgnoreCase(SUPPRESSED) == true) return null
        if (language != null && !language.equalsIgnoreCase("ENG")) return null
        return DrugCandidate(
            rxcui = id,
            name = label,
            tty = (tty ?: fallbackTty)?.trim().orEmpty(),
            synonym = synonym?.trim()?.ifBlank { null }
        )
    }

    private inline fun <reified T> read(json: String): T? = try {
        gson.fromJson(json, T::class.java)
    } catch (_: JsonSyntaxException) {
        null
    }

    private fun String?.equalsIgnoreCase(other: String): Boolean = this?.equals(other, true) == true

    // --- wire shapes -----------------------------------------------------------------------------
    // Deliberately lenient: every field nullable, nothing required. RxNorm omits whole branches for
    // a concept that has nothing to say, and a parser that insists on them fails on the ordinary case.

    private data class DrugsDto(val drugGroup: DrugGroupDto?)
    private data class DrugGroupDto(val name: String?, val conceptGroup: List<ConceptGroupDto>?)
    private data class ConceptGroupDto(val tty: String?, val conceptProperties: List<ConceptDto>?)

    private data class ConceptDto(
        val rxcui: String?,
        val name: String?,
        val synonym: String?,
        val tty: String?,
        val language: String?,
        val suppress: String?
    )

    private data class ApproximateDto(val approximateGroup: ApproximateGroupDto?)
    private data class ApproximateGroupDto(val inputTerm: String?, val candidate: List<CandidateDto>?)
    private data class CandidateDto(
        val rxcui: String?,
        val name: String?,
        val tty: String?,
        val score: String?,
        val rank: String?
    )

    private data class PropertiesDto(val properties: ConceptDto?)

    private data class AllPropertiesDto(val propConceptGroup: PropConceptGroupDto?)
    private data class PropConceptGroupDto(val propConcept: List<PropConceptDto>?)
    private data class PropConceptDto(
        val propCategory: String?,
        val propName: String?,
        val propValue: String?
    )

    private data class RelatedDto(val relatedGroup: RelatedGroupDto?)
    private data class RelatedGroupDto(val rxcui: String?, val conceptGroup: List<ConceptGroupDto>?)
}
