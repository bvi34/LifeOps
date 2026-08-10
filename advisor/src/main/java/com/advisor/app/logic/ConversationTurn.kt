package com.advisor.app.logic

/** One prior turn of the chat, as the pipeline sees it: who spoke and what they said. */
data class ConversationTurn(val fromUser: Boolean, val text: String)

/**
 * Helpers for reasoning over the **recent conversation** rather than only the latest message. The
 * chat history is loaded per question (like the corpus) and threaded through the pipeline so retrieval,
 * the C3A gate, and the prompt all see the turns leading up to the question — that's what lets a
 * follow-up ("who's its author?", "what about the second one?") resolve against what was just said.
 *
 * Pure and dependency-free, like the rest of `logic/`.
 */
object Conversation {

    /** How many trailing turns fold into the retrieval/recall query so a follow-up finds its subject. */
    const val RETRIEVAL_CONTEXT_TURNS = 2

    /**
     * The text to rank retrieval and memory recall against: the last few turns plus the new question,
     * so a terse follow-up still carries the subject words its answer needs. Falls back to just the
     * question when there's no history.
     */
    fun retrievalQuery(history: List<ConversationTurn>, question: String): String {
        if (history.isEmpty()) return question.trim()
        val recent = history.takeLast(RETRIEVAL_CONTEXT_TURNS).joinToString(" ") { it.text.trim() }
        return (recent + " " + question).trim()
    }

    /** True once the chat is underway — the signal that a reference has somewhere to bind. */
    fun hasContext(history: List<ConversationTurn>): Boolean =
        history.any { it.text.isNotBlank() }
}
