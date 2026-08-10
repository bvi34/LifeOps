package com.advisor.app.logic

/**
 * One numbered piece of grounding context handed to the model. [ref] is the citation number the
 * answer refers back to (`[1]`, `[2]`, …); [excerpt] is the (possibly trimmed) document body.
 */
data class ContextBlock(
    val ref: Int,
    val document: KnowledgeDocument,
    val excerpt: String
)

/**
 * The fully-assembled RAG prompt: a system instruction, the retrieved context, and the user's
 * question. It is structured (not a bare string) so the placeholder engine can reason over the
 * blocks directly while a real model consumes [render] — the flat text a GGUF model is actually fed.
 */
data class AdvisorPrompt(
    val system: String,
    val context: List<ContextBlock>,
    val question: String
) {
    /** Flatten to the single prompt string a local model receives. */
    fun render(): String = buildString {
        append(system).append("\n\n")
        if (context.isEmpty()) {
            append("CONTEXT: (none available)\n\n")
        } else {
            append("CONTEXT:\n")
            for (block in context) {
                append('[').append(block.ref).append("] (")
                append(block.document.source.displayName).append(" · ").append(block.document.kind)
                append(") ").append(block.document.title).append('\n')
                append(block.excerpt).append("\n\n")
            }
        }
        append("QUESTION: ").append(question).append('\n')
        append("ANSWER:")
    }
}

/**
 * The **A**ugmentation step: turns a question plus the retriever's chunks into an [AdvisorPrompt].
 * Kept pure and tiny so the exact text the model sees is testable and stable.
 */
object PromptAssembler {

    /** The grounding contract the model is held to — cite sources, refuse ungrounded answers. */
    const val SYSTEM: String =
        "You are Advisor, a private on-device assistant for the Operations Sandbox suite. " +
            "Answer using only the CONTEXT below, which is drawn from the user's own LifeOps, " +
            "Citation and Logistics data. Cite the sources you use as [n]. If the context does not " +
            "answer the question, say so plainly rather than guessing."

    /** Long bodies are trimmed so a small model's context window isn't spent on one row. */
    const val MAX_EXCERPT = 400

    fun assemble(question: String, chunks: List<RetrievedChunk>): AdvisorPrompt {
        val blocks = chunks.mapIndexed { index, chunk ->
            ContextBlock(index + 1, chunk.document, excerpt(chunk.document.body))
        }
        return AdvisorPrompt(SYSTEM, blocks, question.trim())
    }

    private fun excerpt(body: String): String {
        val clean = body.trim()
        if (clean.length <= MAX_EXCERPT) return clean
        val cut = clean.take(MAX_EXCERPT)
        // Prefer a word boundary so we don't slice a word in half.
        val trimmed = cut.substringBeforeLast(' ', cut)
        return "$trimmed…"
    }
}
