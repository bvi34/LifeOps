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
 * The fully-assembled RAG prompt. Beyond the retrieved [context] it now also carries the user's
 * [identity] (always-on persona context), recalled long-term [memories], and any [derived] lines the
 * logic engine produced. It is structured (not a bare string) so the placeholder engine can reason
 * over the parts directly while a real model consumes [render] — the flat text a GGUF model is fed.
 */
data class AdvisorPrompt(
    val system: String,
    val identity: List<String>,
    val profiles: List<Profile>,
    val memories: List<MemoryRecord>,
    val context: List<ContextBlock>,
    val derived: List<String>,
    val question: String
) {
    /** Flatten to the single prompt string a local model receives. */
    fun render(): String = buildString {
        append(system).append("\n\n")

        if (identity.isNotEmpty()) {
            append("IDENTITY (who you are advising):\n")
            for (line in identity) append("- ").append(line).append('\n')
            append('\n')
        }

        if (profiles.isNotEmpty()) {
            append("PROFILES (standing context; reference by name):\n")
            for (profile in profiles) {
                append("## ").append(profile.headerLine()).append('\n')
                for (entry in profile.recentEntries()) append("- ").append(entry.text).append('\n')
            }
            append('\n')
        }

        if (memories.isNotEmpty()) {
            append("MEMORY (long-term recall):\n")
            memories.forEachIndexed { index, memory ->
                append('M').append(index + 1).append(". ")
                if (memory.tags.isNotEmpty()) append('[').append(memory.tags.joinToString(", ")).append("] ")
                append(memory.content).append('\n')
            }
            append('\n')
        }

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

        if (derived.isNotEmpty()) {
            append("REASONING (from the logic engine):\n")
            for (line in derived) append("- ").append(line).append('\n')
            append('\n')
        }

        append("QUESTION: ").append(question).append('\n')
        append("ANSWER:")
    }
}

/**
 * The **A**ugmentation step: turns a question plus identity, recalled memory, retrieved chunks and
 * the logic engine's output into an [AdvisorPrompt]. Kept pure and tiny so the exact text the model
 * sees is testable and stable.
 */
object PromptAssembler {

    /** The grounding contract the model is held to — use what's given, cite sources, don't guess. */
    const val SYSTEM: String =
        "You are Advisor, a private on-device assistant for the Operations Sandbox suite. Answer " +
            "using the user's IDENTITY, the standing PROFILES (referenced by name), recalled MEMORY, " +
            "the CONTEXT drawn from their own LifeOps, Citation and Logistics data, and any REASONING " +
            "provided. Cite app context you use as [n]. If none of it answers the question, say so " +
            "plainly rather than guessing. To save a durable fact to a standing profile, add a line: " +
            "@remember(<profile>): <fact> — use an existing profile key (e.g. user, llm-persona) or " +
            "a new project key. To save a durable fact to long-term memory, add a line: " +
            "@memorize: <fact> #tag1 #tag2 (tags optional)."

    /** Long bodies are trimmed so a small model's context window isn't spent on one row. */
    const val MAX_EXCERPT = 400

    fun assemble(
        question: String,
        chunks: List<RetrievedChunk>,
        identity: Identity = Identity.EMPTY,
        memories: List<MemoryRecord> = emptyList(),
        profiles: List<Profile> = emptyList(),
        derived: List<String> = emptyList()
    ): AdvisorPrompt {
        val blocks = chunks.mapIndexed { index, chunk ->
            ContextBlock(index + 1, chunk.document, excerpt(chunk.document.body))
        }
        return AdvisorPrompt(
            system = SYSTEM,
            identity = identity.toContextLines(),
            profiles = profiles,
            memories = memories,
            context = blocks,
            derived = derived,
            question = question.trim()
        )
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
