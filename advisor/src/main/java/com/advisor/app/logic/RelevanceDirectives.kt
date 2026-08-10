package com.advisor.app.logic

/** The model's verdict on one shown candidate: the citation number [ref] and the [category] it assigned. */
data class RelevanceVote(val ref: Int, val category: RelevanceCategory)

/**
 * The model's **relevance-feedback seam** — the categorical-judgement analogue of [MemoryDirectives]
 * and [ProfileDirectives]. Where those let the model *write*, this lets the model *correct the
 * grounding*: after being shown the numbered CONTEXT candidates, it may flag any that don't fit with a
 * line of the form
 *
 * ```
 * @relevance(2): object_mismatch
 * @relevance(3): state_mismatch
 * @relevance(1): relevant
 * ```
 *
 * The repository parses these into [RelevanceVote]s, maps each `[n]` back to its document, and feeds
 * them to [RelevanceEngine.reassess] so the model's own verdict overrides the deterministic baseline —
 * then answers again over the corrected set. This is the "let the LLM make the parser better" loop:
 * the engine proposes, the model disposes. Pure and testable; the placeholder never emits these, so the
 * loop is inert until a real model is loaded.
 */
object RelevanceDirectives {

    /** Guidance handed to the model so it knows the convention. */
    const val INSTRUCTION: String =
        "If a CONTEXT item doesn't fit what was asked — the wrong kind of thing, or the right kind in " +
            "the wrong state — flag it with a line: @relevance(<n>): <verdict>, where <n> is the " +
            "citation number and <verdict> is one of relevant, irrelevant, object_mismatch, " +
            "state_mismatch, uncertain. Flag the misfits before you rely on what's left; I'll re-check " +
            "your grounding and let you answer again over just the candidates that fit."

    // @relevance(2): state_mismatch  — case-insensitive, one per line.
    private val DIRECTIVE = Regex("""(?im)^[ \t]*@relevance\s*\(\s*(\d+)\s*\)\s*:\s*([A-Za-z_ ]+?)[ \t]*$""")

    fun parse(text: String): List<RelevanceVote> =
        DIRECTIVE.findAll(text)
            .mapNotNull { m ->
                val ref = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val category = categoryOf(m.groupValues[2]) ?: return@mapNotNull null
                RelevanceVote(ref, category)
            }
            .toList()

    /** The reply with relevance-directive lines removed and surrounding blank lines tidied. */
    fun strip(text: String): String =
        text.lineSequence()
            .filterNot { DIRECTIVE.matches(it) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private fun categoryOf(raw: String): RelevanceCategory? =
        when (raw.trim().lowercase().replace(' ', '_')) {
            "relevant", "relevance", "fits", "keep" -> RelevanceCategory.RELEVANT
            "irrelevant", "unrelated", "drop" -> RelevanceCategory.IRRELEVANT
            "object_mismatch", "object_type_mismatch", "type_mismatch", "wrong_type", "wrong_object" ->
                RelevanceCategory.OBJECT_TYPE_MISMATCH
            "state_mismatch", "wrong_state", "status_mismatch" -> RelevanceCategory.STATE_MISMATCH
            "uncertain", "unknown", "unsure", "not_sure" -> RelevanceCategory.UNCERTAIN
            else -> null
        }
}
