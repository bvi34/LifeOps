package com.health.app.logic

/**
 * What Health knows about a *product*, as distinct from what it knows about a person's use of one.
 *
 * A [DrugMonograph] is the manufacturer's own account of a medicine — its ingredients, its dose
 * form, and the sections printed on the label — fetched from two public United States government
 * services and cached verbatim:
 *
 *  - **RxNorm** (`rxnav.nlm.nih.gov`, the National Library of Medicine) names the thing. It is the
 *    vocabulary that knows "Children's Tylenol" and "acetaminophen 160 mg/5 mL oral suspension" are
 *    the same product, which is exactly the lookup a person standing at a cupboard cannot do.
 *  - **openFDA** (`api.fda.gov`) carries the Structured Product Label — the text on the box.
 *
 * Nothing in this file interprets any of it. Health does not compute a paediatric dose, does not
 * rank warnings, and does not decide that a section is irrelevant to you: it stores the label's own
 * words, attributed and dated, and shows them next to the dose rules **you** typed in. That line is
 * the whole reason the feature is safe to have. An app that reads a label to you is a reference; an
 * app that does arithmetic on a label is giving medical advice, and this one is not qualified to.
 *
 * All of it is framework-free and unit-tested — the HTTP hop lives in `data/net`, the parsing in
 * [RxNormParser] and [OpenFdaParser].
 */

/** The services a fact came from, for the attribution line every monograph carries. */
object DrugSources {
    const val RXNORM = "RxNorm (U.S. National Library of Medicine)"
    const val OPENFDA = "openFDA drug label (U.S. Food and Drug Administration)"
}

/**
 * A term type in RxNorm's vocabulary, reduced to the handful Health can say something useful about.
 *
 * The distinction that matters to a user is *how specific* a hit is: "ibuprofen" is a substance,
 * "Advil" is a brand, and "ibuprofen 100 mg/5 mL oral suspension" is a thing with a bottle. The
 * search puts the specific ones first, because a product with a strength is the one whose label
 * openFDA can find.
 */
enum class DrugConceptKind(val ttys: Set<String>, val label: String, val rank: Int) {
    /** A branded product with a strength and a form — "Children's Motrin 100 mg/5 mL suspension". */
    BRANDED_PRODUCT(setOf("SBD", "BPCK"), "Branded product", 0),

    /** The same thing said generically — "ibuprofen 100 mg/5 mL oral suspension". */
    GENERIC_PRODUCT(setOf("SCD", "GPCK"), "Generic product", 1),

    /** A brand with no strength attached — "Motrin". */
    BRAND(setOf("BN"), "Brand", 2),

    /** The substance itself — "ibuprofen". */
    INGREDIENT(setOf("IN", "PIN", "MIN"), "Ingredient", 3),

    /** Anything else RxNorm returned; kept rather than dropped, but sorted last. */
    OTHER(emptySet(), "Other", 4);

    companion object {
        fun fromTty(tty: String?): DrugConceptKind {
            val key = tty?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { key in it.ttys } ?: OTHER
        }
    }
}

/**
 * One hit from a drug search, before anything is fetched about it. [rxcui] is RxNorm's stable
 * concept id and the key everything else hangs off — including the openFDA label lookup.
 */
data class DrugCandidate(
    val rxcui: String,
    val name: String,
    val tty: String,
    val synonym: String? = null,
    /** RxNorm's own match score on the spelling-tolerant path; 0 on the exact-name path. */
    val score: Int = 0
) {
    val kind: DrugConceptKind get() = DrugConceptKind.fromTty(tty)

    /** What to show under the name in a result list: the brand's own wording, or the term type. */
    val subtitle: String get() = synonym?.takeIf { it.isNotBlank() && it != name } ?: kind.label
}

/**
 * One section of a Structured Product Label, kept as the label wrote it.
 *
 * [key] is openFDA's field name so the set is stable across labels; [title] is what a person calls
 * it. Sections arrive in the order [OpenFdaParser.SECTION_ORDER] lists them, which is roughly the
 * order they are useful in at a cupboard: what it's for, how much, then what to watch out for.
 */
data class LabelSection(val key: String, val title: String, val text: String)

/**
 * Everything Health caches about a product. Every field is nullable or empty-able: labels differ
 * wildly in completeness, and a monograph with three facts is still worth more than none.
 *
 * [fetchedAt] is when Health asked, not when the label was written — [labelEffectiveTime] is the
 * label's own date. Both are shown, because "we looked this up in March" and "the manufacturer
 * revised it in 2019" answer different questions about how much to trust it.
 */
data class DrugMonograph(
    val rxcui: String?,
    val name: String,
    val genericName: String? = null,
    val brandName: String? = null,
    val doseForm: String? = null,
    val routes: List<String> = emptyList(),
    val ingredients: List<String> = emptyList(),
    val availableStrengths: List<String> = emptyList(),
    /** DEA schedule ("CII"…) when RxNorm carries one. Absent for everything over the counter. */
    val schedule: String? = null,
    /** openFDA's product type — "HUMAN OTC DRUG LABEL" / "HUMAN PRESCRIPTION DRUG LABEL". */
    val productType: String? = null,
    val manufacturer: String? = null,
    val labelSetId: String? = null,
    /** The label's own revision date, openFDA's `effective_time`, as `yyyyMMdd`. */
    val labelEffectiveTime: String? = null,
    val sections: List<LabelSection> = emptyList(),
    val sources: List<String> = emptyList(),
    val fetchedAt: Long = 0L
) {
    val hasLabel: Boolean get() = sections.isNotEmpty()

    fun section(key: String): LabelSection? = sections.firstOrNull { it.key == key }

    /**
     * The label's own dosing text, if it printed one.
     *
     * Deliberately *not* parsed into numbers. "Take 2 tablets every 4 to 6 hours; do not exceed 6
     * tablets in 24 hours" is a sentence a person reads and applies with judgement — turning it
     * into a dose rule automatically would mean Health guessing which of the label's several dose
     * tables applies to this particular person, which is precisely the guess it must never make.
     */
    val dosageText: String? get() = section(OpenFdaParser.DOSAGE)?.text

    /** A one-line description for a list row: the strength and form, when they're known. */
    val descriptor: String?
        get() = listOfNotNull(
            availableStrengths.firstOrNull(),
            doseForm,
            routes.firstOrNull()?.lowercase()
        ).distinct().joinToString(" · ").ifBlank { null }

    /** "Ibuprofen (Motrin)" — how a person says the thing, when both names are known. */
    val displayName: String
        get() = when {
            brandName.isNullOrBlank() -> name
            genericName.isNullOrBlank() || genericName.equals(brandName, true) -> name
            name.contains(brandName, ignoreCase = true) -> name
            else -> "$name ($brandName)"
        }

    val attribution: String
        get() = if (sources.isEmpty()) "" else "Source: " + sources.joinToString("; ")
}
