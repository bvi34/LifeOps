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
    val question: String,
    val conversation: List<ConversationTurn> = emptyList()
) {
    /**
     * Flatten to a single prompt string.
     *
     * [includeConversation] exists because a chat model must not be handed prior turns as flat text.
     * A line like `Advisor: <previous answer>` sitting in the middle of a user message has nothing
     * marking it as *finished*, so the model reads it as something to continue: it restates the
     * previous answer and appends to it. That answer is then persisted and fed back on the next turn,
     * so every reply grows by the whole of the one before it. [Qwen3ChatFormat] therefore renders the
     * conversation as real ChatML turns, each closed with an end-of-turn token, and asks for the body
     * without it. The flat form stays the default for callers that reason over the text directly.
     */
    fun render(includeConversation: Boolean = true): String = buildString {
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

        if (includeConversation && conversation.isNotEmpty()) {
            append("CONVERSATION (recent turns, oldest first — resolve follow-ups against this):\n")
            for (turn in conversation) {
                append(if (turn.fromUser) "User: " else "Advisor: ").append(turn.text).append('\n')
            }
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
        "You are Advisor, a private on-device assistant for the Operations Sandbox suite. Be warm, " +
            "conversational, and practical: acknowledge what the user seems to mean, translate casual " +
            "phrasing into the app concepts you know (tasks, goals, books, notes, groceries, pantry, " +
            "recipes), and answer in plain language rather than database-speak. Fuse the data into the " +
            "reply: open with a short, natural sentence, phrased freshly each time, that frames what you " +
            "found, and weave the specifics into it rather than dumping a bare list. Never repeat or " +
            "restate an earlier reply — answer only what was just asked. Use the user's " +
            "IDENTITY, the standing PROFILES (referenced by name), recalled MEMORY, the CONTEXT drawn " +
            "from their own LifeOps, Citation and Logistics data, the recent CONVERSATION (to resolve " +
            "follow-up references like \"it\" or \"that\"), and any REASONING provided. Cite app " +
            "context you use as [n]. If none of it answers the question, say so plainly rather than " +
            "guessing, and ask one natural follow-up question. To save a durable fact to a standing " +
            "profile, add a line: @remember(<profile>): <fact> — use an existing profile key (e.g. " +
            "user, llm-persona) or a new project key. To save a durable fact to long-term memory, add " +
            "a line: @memorize: <fact> #tag1 #tag2 (tags optional). You cannot create, change or " +
            "delete anything in LifeOps, Citation or Logistics — you only read them. Requests to add " +
            "a task are carried out before they ever reach you, so if one is in front of you it was " +
            "not understood: never say you added, created or changed a task, goal or project. Ask for " +
            "it plainly instead (for example: \"add a task to LifeOps called <title>\"). " +
            RelevanceDirectives.INSTRUCTION

    /** Long bodies are trimmed so a small model's context window isn't spent on one row. */
    const val MAX_EXCERPT = 400

    /**
     * The citation numbering [chunks] are shown under — `[1]`, `[2]`, … in retrieval order. Exposed
     * because the numbering has to be reproducible outside prompt assembly: reading an answer's `[n]`
     * markers back means knowing which document each one stood for, and a second copy of "index + 1"
     * elsewhere would be a numbering that could silently drift from the one the model saw.
     */
    fun blocks(chunks: List<RetrievedChunk>): List<ContextBlock> =
        chunks.mapIndexed { index, chunk ->
            ContextBlock(index + 1, chunk.document, excerpt(chunk.document.body))
        }

    fun assemble(
        question: String,
        chunks: List<RetrievedChunk>,
        identity: Identity = Identity.EMPTY,
        memories: List<MemoryRecord> = emptyList(),
        profiles: List<Profile> = emptyList(),
        derived: List<String> = emptyList(),
        conversation: List<ConversationTurn> = emptyList(),
        system: String = SYSTEM
    ): AdvisorPrompt {
        val blocks = blocks(chunks)
        return AdvisorPrompt(
            system = system.trim().ifBlank { SYSTEM },
            identity = identity.toContextLines(),
            profiles = profiles,
            memories = memories,
            context = blocks,
            derived = derived,
            question = question.trim(),
            conversation = conversation
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
