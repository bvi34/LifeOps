package com.health.app.logic

/**
 * What a household has written down about what it must not be given — and the one check Health is
 * qualified to make against it.
 *
 * Until now an allergy lived in a profile's free-text note, alongside "conditions" and "the doctor's
 * number". That is the most safety-critical fact in the app stored in the one shape nothing can read
 * back: a note cannot be listed, cannot be sorted by how badly it went last time, and above all
 * cannot be compared against the bottle somebody is holding. This file is the readable version.
 *
 * ### The line this file does not cross
 *
 * Health matches **what was written against what the label says**, and nothing else. It does not
 * know that penicillin and amoxicillin are relatives, that a sulfa allergy has anything to do with a
 * thiazide, or that somebody reacting to one NSAID may react to the next. Those are real, and they
 * are *pharmacology* — a judgement about drug classes that this app is no more qualified to make
 * than it is to read a dose off a label (see `DrugFacts`). An app that inferred a class would be
 * giving medical advice; an app that matches an ingredient list is doing a lookup.
 *
 * So a warning here always means one literal thing, and [AllergyWarning.matchedOn] always says what
 * it was. **The absence of a warning is never a clearance** — it means nothing recorded matched, and
 * the caller is expected to say so rather than render a reassuring green tick.
 */

/** What a recorded allergy is to. Only [DRUG] takes part in the medicine check — see [Allergies.check]. */
enum class AllergyKind(val key: String, val label: String) {
    DRUG("drug", "Medicine"),
    FOOD("food", "Food"),
    ENVIRONMENT("environment", "Environmental"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?): AllergyKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * How badly it went last time.
 *
 * Ordered, so "the worst thing recorded about this person" is a `maxOf` rather than a re-derivation —
 * the same trick [CareLevel] uses, and for the same reason: several screens ask that question and
 * they must not answer it differently.
 *
 * [UNKNOWN] sorts lowest deliberately. A severity nobody recorded should not out-shout a reaction
 * somebody described as anaphylaxis, and it must not be silently promoted to "severe" either — the
 * honest reading of an empty field is that the field is empty, and the label says exactly that.
 */
enum class AllergySeverity(val key: String, val label: String) {
    UNKNOWN("unknown", "Severity not recorded"),
    MILD("mild", "Mild"),
    MODERATE("moderate", "Moderate"),
    SEVERE("severe", "Severe"),
    ANAPHYLAXIS("anaphylaxis", "Anaphylaxis");

    /** Whether this is the kind of reaction that belongs on an emergency sheet in its own right. */
    val isDangerous: Boolean get() = this == SEVERE || this == ANAPHYLAXIS

    companion object {
        fun fromKey(key: String?): AllergySeverity = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/**
 * One recorded allergy, reduced to what matching looks at. The database row carries more — when it
 * was first noticed, what the reaction was, who confirmed it — but none of that changes whether a
 * bottle matches, so none of it appears here.
 */
data class AllergyFacts(
    val id: String,
    /** As written down: "penicillin", "peanuts", "amoxicillin". Matched as words, never as a blob. */
    val substance: String,
    val kind: AllergyKind,
    val severity: AllergySeverity,
    /** The RxNorm concept, when the allergy was recorded by lookup rather than typed. Exact when present. */
    val rxcui: String? = null
)

/**
 * The product being considered, as `drug_facts` already caches it. [ingredients] is the field that
 * does the real work here — it is the label's own list, which is why a household that looked a
 * medicine up gets a better check than one that typed the name in.
 */
data class MedicineFacts(
    val rxcui: String? = null,
    val name: String,
    val brandName: String? = null,
    val genericName: String? = null,
    val ingredients: List<String> = emptyList()
)

/**
 * How a warning was arrived at. These are ordered by how much they prove, and the order is the point:
 * an ingredient list is the label's own statement of what is in the bottle, while a name is a piece
 * of marketing that happens to contain a word.
 */
enum class AllergyMatch(val label: String) {
    /** The same RxNorm concept. Not a resemblance — the identifier says it is the same product. */
    CONCEPT("Recorded against this exact medicine"),

    /** The label lists an ingredient the recorded allergy names. */
    INGREDIENT("This contains it"),

    /** Only the way the product is named matches. Weaker, and said to be weaker. */
    NAME("The name matches")
}

/**
 * One reason to stop, with the evidence attached.
 *
 * [matchedOn] is the string that actually matched, quoted from the label or from what the household
 * typed — never a paraphrase. A warning a person can't audit is a warning they will learn to dismiss.
 */
data class AllergyWarning(
    val allergy: AllergyFacts,
    val match: AllergyMatch,
    val matchedOn: String
) {
    /** "Penicillin — anaphylaxis". The line a dialog puts in bold. */
    val headline: String
        get() = if (allergy.severity == AllergySeverity.UNKNOWN) allergy.substance.trim()
        else "${allergy.substance.trim()} — ${allergy.severity.label.lowercase()}"

    /** "This contains it: amoxicillin trihydrate". What the warning is actually based on. */
    val detail: String
        get() = if (matchedOn.equals(allergy.substance.trim(), ignoreCase = true)) match.label
        else "${match.label}: $matchedOn"
}

object Allergies {

    /**
     * The standing caveat on every allergy check. Shown wherever [check] is, for the same reason
     * `Fever.DISCLAIMER` is shown wherever a temperature is banded.
     */
    const val DISCLAIMER: String =
        "Health only compares what you have written down against what the label says. It does not " +
            "know which medicines are related to each other. No warning here does not mean it is safe " +
            "— ask a pharmacist or a doctor."

    /**
     * Everything recorded that matches this product, worst first.
     *
     * **Only [AllergyKind.DRUG] allergies are considered**, and that is a limitation rather than an
     * oversight. Health holds a label's active ingredients, not its excipients — so it genuinely
     * cannot tell whether a grape-flavoured suspension is a problem for a grape allergy, and a check
     * that fired on the flavour in the product's name would be inventing a fact about the formulation.
     * Non-drug allergies stay on the person's record, where a human reads them, and are not pretended
     * to have been checked.
     *
     * An empty list means **nothing recorded matched** — see the note on this file. It is not a
     * clearance and callers must not render it as one.
     */
    fun check(allergies: List<AllergyFacts>, medicine: MedicineFacts): List<AllergyWarning> {
        val candidates = buildList {
            medicine.ingredients.forEach { add(AllergyMatch.INGREDIENT to it) }
            listOfNotNull(medicine.name, medicine.brandName, medicine.genericName)
                .forEach { add(AllergyMatch.NAME to it) }
        }.filter { it.second.isNotBlank() }

        return allergies
            .filter { it.kind == AllergyKind.DRUG }
            .mapNotNull { allergy -> match(allergy, medicine, candidates) }
            // Worst first, and within a severity the strongest evidence first: the reader is meant to
            // stop at the top of this list, so the top of it has to be the thing most worth stopping for.
            .sortedWith(
                compareByDescending<AllergyWarning> { it.allergy.severity.ordinal }
                    .thenBy { it.match.ordinal }
                    .thenBy { it.allergy.substance.lowercase() }
            )
    }

    private fun match(
        allergy: AllergyFacts,
        medicine: MedicineFacts,
        candidates: List<Pair<AllergyMatch, String>>
    ): AllergyWarning? {
        // An identifier beats every amount of string comparison, and settles the case where the
        // household recorded the allergy by looking the medicine up.
        val allergyRxcui = allergy.rxcui?.trim()?.ifBlank { null }
        val medicineRxcui = medicine.rxcui?.trim()?.ifBlank { null }
        if (allergyRxcui != null && allergyRxcui == medicineRxcui) {
            return AllergyWarning(allergy, AllergyMatch.CONCEPT, allergy.substance.trim())
        }

        val substance = tokenize(allergy.substance)
        if (substance.isEmpty()) return null

        // Ingredients are checked before names, so a product that matches both is reported on the
        // evidence that actually proves something.
        return candidates
            .firstOrNull { (_, text) ->
                val candidate = tokenize(text)
                contains(candidate, substance) && !declaredFreeOf(candidate, substance)
            }
            ?.let { (kind, text) -> AllergyWarning(allergy, kind, text.trim()) }
    }

    /** The worst thing recorded about a person, for the badge that says there is something to read. */
    fun worstSeverity(allergies: List<AllergyFacts>): AllergySeverity? =
        allergies.maxByOrNull { it.severity.ordinal }?.severity

    /**
     * Words, lowercased, with punctuation treated as a gap.
     *
     * Comparing whole strings would miss "amoxicillin trihydrate" for a recorded "amoxicillin", and
     * comparing raw substrings would match "codeine" inside "hydrocodone" — a different drug, and
     * exactly the false alarm that teaches a household to tap past this dialog.
     */
    internal fun tokenize(text: String): List<String> =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }

    /** Whether [substance] appears in [candidate] as a contiguous run of whole words. */
    private fun contains(candidate: List<String>, substance: List<String>): Boolean =
        indexOfRun(candidate, substance) >= 0

    /**
     * Whether the text is saying the product **does not** contain the substance.
     *
     * "Aspirin-free" and "sugar free" are the common ones, and they are printed on exactly the
     * products somebody with that allergy is reaching for. Reading them as a match would be the most
     * embarrassing possible failure of this feature: a warning fired by the words put on the box to
     * reassure the very person being warned.
     */
    private fun declaredFreeOf(candidate: List<String>, substance: List<String>): Boolean {
        var from = 0
        var found = false
        while (true) {
            val at = indexOfRun(candidate, substance, from)
            // Out of occurrences. Everything we did find was negated — which is the whole claim.
            // Having found none at all is not a denial of anything, so it is not one here either.
            if (at < 0) return found
            found = true
            val after = candidate.getOrNull(at + substance.size)
            val before = candidate.getOrNull(at - 1)
            val negated = after in NEGATIONS_AFTER || before in NEGATIONS_BEFORE
            // Every occurrence has to be negated. "Sugar free aspirin" mentions it plainly once, and
            // one un-negated mention is still a mention.
            if (!negated) return false
            from = at + 1
        }
    }

    /** Index of the first contiguous occurrence of [run] in [tokens] at or after [from], else -1. */
    private fun indexOfRun(tokens: List<String>, run: List<String>, from: Int = 0): Int {
        if (run.isEmpty() || run.size > tokens.size) return -1
        for (i in from..tokens.size - run.size) {
            if ((run.indices).all { tokens[i + it] == run[it] }) return i
        }
        return -1
    }

    private val NEGATIONS_AFTER = setOf("free")
    private val NEGATIONS_BEFORE = setOf("without", "no")
}
