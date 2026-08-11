package com.advisor.app.logic

/**
 * Describes the language model behind the Advisor, so the UI can honestly show "what's running". It
 * describes either the real weights — a local **Qwen3-4B**, Q4_K_M GGUF, loaded on-device (see
 * [Qwen3LlmEngine]) — or the [PlaceholderLlmEngine] that stands in until those weights are present.
 */
data class ModelSpec(
    val name: String,
    val parameters: String,
    val quantization: String,
    val isPlaceholder: Boolean
) {
    /** A one-line "Gemma-2-2B · Q4_K_M" style label for the UI. */
    fun label(): String = buildString {
        append(name)
        append(" · ").append(parameters)
        append(" · ").append(quantization)
        if (isPlaceholder) append(" (placeholder)")
    }
}

/**
 * The **G**eneration step. The real implementation ([Qwen3LlmEngine]) loads a Qwen3-4B GGUF over
 * llama.cpp and runs the assembled prompt through it entirely on-device; [PlaceholderLlmEngine] stands
 * in until the weights are present. Everything upstream — permissions, retrieval, prompt assembly — is
 * model-agnostic, so this one interface is the only seam the model plugs into.
 */
interface LocalLlmEngine {
    val spec: ModelSpec

    /**
     * A one-line, ground-truth diagnostic of what the generation layer is actually doing — the loaded
     * model file when a real model is running, or *why* it's still on the placeholder (native runtime
     * not in the build / no model file installed / a file that failed to load). Surfaced on the model
     * card so "why am I on the placeholder?" has a precise answer instead of a guess.
     */
    val status: String get() = ""

    /** Produce an answer for [prompt]. Must run fully on-device; no network, no I/O. */
    fun generate(prompt: AdvisorPrompt): String
}

/**
 * A deterministic stand-in for the local model. It does **not** invent language — it composes a
 * grounded, extractive answer straight from the retrieved context, so the end-to-end pipeline is
 * demonstrably working (permissions gate → retrieval → citations) while the real weights are still
 * being chosen. When there is no context it says so and points at the permission gate, because
 * "nothing granted / nothing matched" is the honest answer, not a hallucinated one.
 */
class PlaceholderLlmEngine : LocalLlmEngine {

    override val spec = ModelSpec(
        name = "sandbox-advisor-local",
        parameters = "2–4B",
        quantization = "Q4_K_M / GGUF",
        isPlaceholder = true
    )

    override val status: String get() = "No language model loaded — using the deterministic placeholder."

    override fun generate(prompt: AdvisorPrompt): String {
        val hasContext = prompt.context.isNotEmpty()
        val hasMemory = prompt.memories.isNotEmpty()
        val hasProfiles = prompt.profiles.isNotEmpty()
        val hasIdentity = prompt.identity.isNotEmpty()
        val hasConversation = prompt.conversation.isNotEmpty()

        if (!hasContext && !hasMemory && !hasProfiles && !hasIdentity && !hasConversation) {
            return "I couldn't find anything in your granted data or long-term memory to answer " +
                "that.\n\nThis is a placeholder assistant: it retrieves and cites your own records " +
                "but does not yet run a language model. Check that the relevant app is enabled in " +
                "Permissions, add a memory, or rephrase using words that appear in your data."
        }

        // The identity/profile facts this specific question is about — the actual entries, not just
        // the profile names. Surfacing these is what makes "what is my name" answer from the profile.
        val personalFacts = personalFacts(prompt)

        return buildString {
            if (personalFacts.isNotEmpty()) {
                append(personalLead(prompt.question))
                for (fact in personalFacts) append("\n• ").append(fact)
            }

            if (hasContext) {
                if (isNotEmpty()) append("\n\n")
                append(contextLead(prompt))
                for (block in prompt.context) {
                    append("\n• ")
                    append(block.document.title.ifBlank { block.document.kind })
                    val detail = firstLine(block.excerpt)
                    if (detail.isNotBlank() && !detail.equals(block.document.title, ignoreCase = true)) {
                        append(" — ").append(detail)
                    }
                    append(" [").append(block.ref).append(']')
                }
            }

            if (hasMemory) {
                val sole = isEmpty()
                if (!sole) append("\n\n")
                append(if (sole) "Here's what I remembered:" else "This also jogged my memory —")
                prompt.memories.forEachIndexed { index, memory ->
                    append("\n• ").append(firstLine(memory.content))
                    if (memory.tags.isNotEmpty()) append(" (").append(memory.tags.joinToString(", ")).append(')')
                    append(" [M").append(index + 1).append(']')
                }
            }

            // A profiles-only turn (no data, no memory) still deserves a warm, useful reply rather than
            // silence — name what standing context is in play so the user knows what I'm working from.
            if (isEmpty() && hasProfiles) {
                append("I don't have a specific record for that, but I've got your ")
                append(humanJoin(prompt.profiles.map { it.name }))
                append(if (prompt.profiles.size == 1) " profile in mind." else " profiles in mind.")
                append(" Ask me anything about them, or tell me something new to remember.")
            }

            append("\n\n(On-device placeholder — I'm surfacing your own data here; a local ")
            append(spec.parameters).append(" model will turn this into a fuller reply.)")
        }
    }

    /** Warm lead-in for a profile/identity answer, tuned to whether the question is about the user. */
    private fun personalLead(question: String): String =
        if (IdentityQuestions.isAboutUser(question)) "Here's what I've got about you:"
        else "Here's what I have on that from your profile:"

    /**
     * A conversational lead-in that fuses the data into a sentence — "It looks like today you have …"
     * for list/when-scoped questions ("what's due today?", "what should I tackle this week?"), and a
     * plainer "Here's what I found in your …" for factual look-ups. The bullets (with their [n]
     * citations) follow either way.
     */
    private fun contextLead(prompt: AdvisorPrompt): String {
        val sources = humanJoin(prompt.context.map { it.document.source.displayName }.distinct())
        val scope = scopePhrase(prompt.question)
        val listLike = scope.isNotEmpty() || LIST_HINT.containsMatchIn(prompt.question.lowercase())
        return if (listLike) {
            val prefix = if (scope.isNotEmpty()) "$scope " else ""
            "It looks like ${prefix}you have ${countWord(prompt.context.size)} in your $sources:"
        } else {
            "Here's what I found in your $sources:"
        }
    }

    /** The time window the question is about, for the "It looks like <today> you have …" lead, or "". */
    private fun scopePhrase(question: String): String {
        val q = question.lowercase()
        return when {
            "today" in q -> "today"
            "tonight" in q -> "tonight"
            "tomorrow" in q -> "tomorrow"
            "this week" in q || Regex("\\bweek\\b").containsMatchIn(q) -> "this week"
            "this month" in q -> "this month"
            else -> ""
        }
    }

    private fun countWord(n: Int): String = when (n) {
        1 -> "one thing"
        2 -> "a couple of things"
        else -> "$n things"
    }

    private fun humanJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    private fun firstLine(text: String): String =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

    /**
     * The identity lines and profile entries worth quoting back for [prompt]'s question. For an
     * explicit identity question ("who am I") the whole user profile and identity are fair game, since
     * the wording won't overlap the stored "Name: …" lines; otherwise only entries that share a term
     * with the question are surfaced, so unrelated questions don't dump the profile.
     */
    private fun personalFacts(prompt: AdvisorPrompt): List<String> {
        val aboutUser = IdentityQuestions.isAboutUser(prompt.question)
        // A terse follow-up ("and my email?") carries its subject in the previous user turn, so match
        // against the recent conversation too, not just this message.
        val recentUserTurn = prompt.conversation.lastOrNull { it.fromUser }?.text.orEmpty()
        val queryTerms = (Retriever.tokenize(prompt.question) + Retriever.tokenize(recentUserTurn)).toSet()
        val facts = LinkedHashSet<String>()

        for (line in prompt.identity) {
            if (aboutUser || overlaps(line, queryTerms)) facts += line.trim()
        }
        for (profile in prompt.profiles) {
            val takeAll = aboutUser && profile.kind == ProfileKind.USER
            for (entry in profile.recentEntries()) {
                if (takeAll || overlaps(entry.text, queryTerms)) facts += entry.text.trim()
            }
        }
        return facts.filter { it.isNotBlank() }
    }

    private fun overlaps(text: String, queryTerms: Set<String>): Boolean =
        queryTerms.isNotEmpty() && Retriever.tokenize(text).any { it in queryTerms }

    private companion object {
        // Phrasings that read as "list what I have" rather than a factual look-up — they get the
        // "It looks like you have …" fused lead. Kept broad but content-bearing (no bare greetings).
        val LIST_HINT = Regex(
            "\\b(due|pending|upcoming|coming up|left|remaining|outstanding|scheduled|" +
                "on my plate|to-?do|todos?|tackle|priorit(?:y|ies)|deadlines?|" +
                "should i|do i have|have i got|what'?s on|what do i|anything (?:due|left|pending))\\b"
        )
    }
}
