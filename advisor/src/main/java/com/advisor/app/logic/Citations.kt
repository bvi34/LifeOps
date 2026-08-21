package com.advisor.app.logic

/**
 * The `[n]` markers an answer used to cite its grounding — the inverse of the numbering
 * [PromptAssembler] hands the model in [ContextBlock.ref].
 *
 * This exists to answer one question cheaply: *did the answer actually lean on this candidate?* A
 * generation on a phone costs tens of seconds, so knowing which context a reply leaned on is what
 * separates a re-run that changes the answer from one that reproduces it word for word.
 *
 * Deliberately literal. A bare `[3]` is a citation; anything else that happens to contain digits in
 * brackets (`[2026]`, `[M1]`, a markdown link) is not, so a stray number in the prose can't be read
 * as reliance on a source.
 */
object Citations {

    private val MARKER = Regex("""\[(\d{1,3})]""")

    /** Every context ref cited in [text], in no particular order. */
    fun refs(text: String): Set<Int> =
        MARKER.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()

    /** The subset of [blocks]' document ids that [text] cites. */
    fun citedIds(text: String, blocks: List<ContextBlock>): Set<String> {
        val cited = refs(text)
        if (cited.isEmpty()) return emptySet()
        return blocks.filter { it.ref in cited }.map { it.document.id }.toSet()
    }
}
