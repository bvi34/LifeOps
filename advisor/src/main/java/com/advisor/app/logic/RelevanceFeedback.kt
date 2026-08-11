package com.advisor.app.logic

/**
 * The verdict on a single retrieved candidate — the **categorical feedback** vocabulary the grounding
 * step (and, later, the LLM) speaks. Retrieval ranks by surface/semantic similarity and will happily
 * return a pantry item for "what am I reading?"; this vocabulary is how the engine *names why* a
 * candidate does or doesn't ground the answer, instead of dumping the whole ranked list:
 *
 *  - [RELEVANT] — right kind of thing, right state; it should ground the answer.
 *  - [OBJECT_TYPE_MISMATCH] — wrong kind of thing ("Miracle Whip" is a pantry item, not a book).
 *  - [STATE_MISMATCH] — right kind, wrong state (a `to_read` book when the question asked what's being
 *    *read* now).
 *  - [IRRELEVANT] — shares words but doesn't fit; a catch-all rejection.
 *  - [UNCERTAIN] — the engine has no confident facet opinion (the question named no type/state, or the
 *    candidate's facets are unknown). Kept as grounding, because "not sure" is not "no".
 */
enum class RelevanceCategory {
    RELEVANT,
    IRRELEVANT,
    OBJECT_TYPE_MISMATCH,
    STATE_MISMATCH,
    UNCERTAIN;

    /** Whether a candidate with this verdict should still ground the answer. */
    val grounds: Boolean get() = this == RELEVANT || this == UNCERTAIN
}

/**
 * One candidate judged: the [chunk] it's about, the [category] verdict, the facets that drove it, and a
 * short human [reason]. This is the record the LLM can *override* — supplying its own [category] for a
 * document id — so the deterministic pass is only a baseline the model can correct.
 */
data class CandidateAssessment(
    val chunk: RetrievedChunk,
    val category: RelevanceCategory,
    val objectType: ObjectType,
    val state: String?,
    val reason: String
) {
    val document: KnowledgeDocument get() = chunk.document
}

/**
 * The outcome of grounding a question against its retrieved candidates: every [assessment], plus the
 * convenience views the pipeline needs. [grounding] is what actually feeds the answer (relevant +
 * uncertain); [rejected] is what was filtered and why, so the assistant can be honest about near-misses
 * ("nothing you're reading right now, but you have 2 on your to-read list").
 */
data class GroundingResult(
    val query: QueryFacets,
    val assessments: List<CandidateAssessment>
) {
    val grounding: List<RetrievedChunk> get() = assessments.filter { it.category.grounds }.map { it.chunk }
    val relevant: List<CandidateAssessment> get() = assessments.filter { it.category == RelevanceCategory.RELEVANT }
    val rejected: List<CandidateAssessment> get() = assessments.filter { !it.category.grounds }

    /** A one-line note on what was filtered, for the prompt/logic context (empty when nothing was). */
    fun filterNote(): String {
        if (rejected.isEmpty()) return ""
        val objMiss = rejected.count { it.category == RelevanceCategory.OBJECT_TYPE_MISMATCH }
        val stateMiss = rejected.count { it.category == RelevanceCategory.STATE_MISMATCH }
        val parts = ArrayList<String>()
        if (objMiss > 0) parts += "$objMiss off-type"
        if (stateMiss > 0) parts += "$stateMiss wrong-state"
        val other = rejected.size - objMiss - stateMiss
        if (other > 0) parts += "$other unrelated"
        return "Relevance filter dropped ${parts.joinToString(", ")} candidate(s)."
    }
}

/**
 * The **grounding judge**. Given a question and its retrieved candidates, it infers what the question is
 * asking for ([QueryFacets]) and assigns each candidate a [RelevanceCategory], then exposes the filtered
 * grounding set. This is the "let the engine/parser be better" seam the LLM plugs into:
 *
 *  - [assess] is the deterministic baseline — pure, JVM-testable, and conservative (it only rejects a
 *    candidate when the question named a facet the candidate clearly contradicts; a question with no
 *    facet opinion leaves the ranked list untouched, so generic queries are unaffected).
 *  - [reassess] applies a map of the LLM's own verdicts over that baseline, so once a real model is in
 *    front of it the model's categorical feedback ("this one's a state mismatch") wins.
 */
object RelevanceEngine {

    fun assess(question: String, chunks: List<RetrievedChunk>): GroundingResult {
        val facets = QueryFacets.infer(question)
        val assessments = chunks.map { judge(it, facets) }
        return refine(GroundingResult(facets, assessments))
    }

    /** Re-run grounding with the LLM's own category overrides (keyed by document id) layered on top. */
    fun reassess(base: GroundingResult, overrides: Map<String, RelevanceCategory>): GroundingResult {
        if (overrides.isEmpty()) return base
        val updated = base.assessments.map { a ->
            val override = overrides[a.document.id] ?: return@map a
            a.copy(category = override, reason = "Set by model feedback: ${override.name.lowercase()}.")
        }
        return refine(base.copy(assessments = updated))
    }

    // --- baseline judging ---

    private fun judge(chunk: RetrievedChunk, facets: QueryFacets): CandidateAssessment {
        val type = KnowledgeFacets.objectTypeOf(chunk.document)
        val state = KnowledgeFacets.stateOf(chunk.document)

        // No facet opinion at all → defer to retrieval's own ranking.
        if (!facets.hasOpinion) {
            return CandidateAssessment(chunk, RelevanceCategory.UNCERTAIN, type, state,
                "No type/state named in the question; kept on retrieval score.")
        }

        if (facets.objectTypes.isNotEmpty()) {
            if (type == ObjectType.UNKNOWN) {
                return CandidateAssessment(chunk, RelevanceCategory.UNCERTAIN, type, state,
                    "Can't tell this candidate's type; kept to be safe.")
            }
            if (type !in facets.objectTypes) {
                val want = facets.objectTypes.joinToString("/") { it.label }
                return CandidateAssessment(chunk, RelevanceCategory.OBJECT_TYPE_MISMATCH, type, state,
                    "Wanted a $want; this is a ${type.label}.")
            }
        }

        if (facets.state != null && state != null && state != facets.state) {
            return CandidateAssessment(chunk, RelevanceCategory.STATE_MISMATCH, type, state,
                "Wanted state '${facets.state}'; this is '$state'.")
        }

        return CandidateAssessment(chunk, RelevanceCategory.RELEVANT, type, state,
            "Matches ${type.label}${facets.state?.let { " · $it" } ?: ""}.")
    }

    /**
     * Grounding policy over the judged set: if any candidate is [RelevanceCategory.RELEVANT], keep only
     * the relevant ones (drop the uncertain tail too — we found real matches). If none are relevant but
     * some are uncertain, keep the uncertain (we had no confident opinion, so don't throw the list
     * away). If everything is a hard mismatch, keep nothing — the honest "no fit" the caller turns into
     * a clarification or a near-miss note.
     */
    private fun refine(result: GroundingResult): GroundingResult {
        val hasRelevant = result.assessments.any { it.category == RelevanceCategory.RELEVANT }
        if (!hasRelevant) return result
        val trimmed = result.assessments.map { a ->
            if (a.category == RelevanceCategory.UNCERTAIN)
                a.copy(category = RelevanceCategory.IRRELEVANT,
                    reason = "Stronger, on-type matches were found; set aside.")
            else a
        }
        return result.copy(assessments = trimmed)
    }
}
