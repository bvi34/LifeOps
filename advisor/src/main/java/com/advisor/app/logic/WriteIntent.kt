package com.advisor.app.logic

/**
 * The writes a single user turn is *explicitly asking for*. When the user issues a plain-language
 * command — "add to LLM persona that you are called Ava", "remember that my dog is Rex #pets" — this
 * captures the profile and/or memory writes it implies.
 */
data class WriteIntentResult(
    val profileAppends: List<ProfileAppend> = emptyList(),
    val memoryWrites: List<MemoryWrite> = emptyList()
) {
    val hasWrites: Boolean get() = profileAppends.isNotEmpty() || memoryWrites.isNotEmpty()

    companion object {
        val NONE = WriteIntentResult()
    }
}

/**
 * Turns an **explicit user write-command** into structured [ProfileAppend]/[MemoryWrite]s, so
 * "remember this for me" actually persists — deterministically, without needing the language model to
 * emit a `@remember`/`@memorize` directive itself. It is the command counterpart to the model-driven
 * [ProfileDirectives]/[MemoryDirectives] paths: this fires when *the user* dictates a write, those fire
 * when *the model* chooses to.
 *
 * Deliberately conservative — it only matches imperative phrasings ("add to …", "remember that …",
 * "save … to memory") and treats anything ending in a question mark as a question, not a command — so
 * ordinary recall questions ("do you remember what I said?") fall through to normal Q&A untouched. Pure
 * and JVM-testable; the repository runs it before the C3A gate and performs any writes it returns.
 */
object WriteIntent {

    // Verbs that, together with a "to <profile>" target, mean "append to that standing profile".
    private const val PROFILE_VERB =
        "(?:add|append|save|note|write|record|put|store|remember|update|set|jot)"

    // Verbs that, at the very start of the turn, mean "commit the rest to long-term memory".
    private val MEMORY_LEAD = Regex(
        """(?i)^(?:please\s+|pls\s+|hey,?\s+|can\s+you\s+|could\s+you\s+|would\s+you\s+)*""" +
            """(?:remember|memorize|memorise|note|record|store|jot(?:\s+down)?|make\s+a\s+note(?:\s+of)?|""" +
            """keep\s+in\s+mind|don'?t\s+forget)\b[ \t:,-]*(.+)""",
        RegexOption.DOT_MATCHES_ALL
    )

    // "save this to memory: …" / "add … to my long-term memory - …" — a memory target named outright.
    private val MEMORY_TARGET = Regex(
        """(?i)\b(?:add|save|put|store|note|record|write|commit|jot)\b.*?\bto\b\s+""" +
            """(?:my\s+|the\s+)?(?:long[- ]?term\s+)?memory\b[ \t:,-]*(?:that\s+|this\s+)?(.+)""",
        RegexOption.DOT_MATCHES_ALL
    )

    // Interrogatives that unmask a false "remember when …" / "note how …" as a question, not a command.
    private val INTERROGATIVE_LEAD =
        Regex("""(?i)^(?:when|what|where|who|whom|whose|which|how|why|if|whether)\b""")

    private val LEADING_CONNECTOR = Regex("""(?i)^(?:that|this|to)\s+""")
    private val TRAILING_FILLER = Regex("""(?i)[ \t]+(?:now|please|thanks|thank\s+you|for\s+me|ok)\s*[.!]*$""")

    /**
     * Interpret [question] against the known [profiles]. Returns [WriteIntentResult.NONE] when it isn't
     * an explicit write command. A profile-directed command wins over a memory one (it named a target).
     */
    fun detect(question: String, profiles: List<Profile>): WriteIntentResult {
        val q = question.trim()
        if (q.isEmpty() || q.endsWith("?")) return WriteIntentResult.NONE

        profileAppend(q, profiles)?.let { return WriteIntentResult(profileAppends = listOf(it)) }
        memoryWrite(q)?.let { return WriteIntentResult(memoryWrites = listOf(it)) }
        return WriteIntentResult.NONE
    }

    /** A "<verb> … to <known profile> [profile] [that|:] <fact>" command, or null. */
    private fun profileAppend(q: String, profiles: List<Profile>): ProfileAppend? {
        // Longest target name first, so "LLM persona" wins over a hypothetical bare "persona".
        val targets = profiles
            .flatMap { p -> nameVariants(p).map { it to p.key } }
            .sortedByDescending { it.first.length }
        for ((variant, key) in targets) {
            val pattern = Regex(
                """(?i)\b$PROFILE_VERB\b.*?\b(?:to|in|into)\b\s+(?:my\s+|the\s+|your\s+)?""" +
                    Regex.escape(variant) + """(?:\s+profile)?\b\s*[:,\-]?\s*(?:that\s+|saying\s+|as\s+)?(.+)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val fact = pattern.find(q)?.groupValues?.get(1)?.let(::clean).orEmpty()
            if (fact.isNotBlank()) return ProfileAppend(key, fact)
        }
        return null
    }

    /** A "remember/note … that <fact>" (or "… to memory: <fact>") command, or null. */
    private fun memoryWrite(q: String): MemoryWrite? {
        val body = MEMORY_LEAD.find(q)?.groupValues?.get(1)
            ?: MEMORY_TARGET.find(q)?.groupValues?.get(1)
            ?: return null
        if (INTERROGATIVE_LEAD.containsMatchIn(body.trim())) return null // "remember when …" is a question
        // Reuse the directive tag-parser so spoken #hashtags become recall tags.
        val write = MemoryDirectives.toWrite(body)
        val content = clean(write.content)
        return if (content.isBlank()) null else write.copy(content = content)
    }

    /** All the ways a profile might be named in a sentence: its label and its key (kebab or spaced). */
    private fun nameVariants(p: Profile): List<String> =
        listOf(p.name, p.key, p.key.replace('-', ' '))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    /** Trim a captured fact of leading connectors ("that"/"to") and trailing politeness/punctuation. */
    private fun clean(raw: String): String =
        raw.trim()
            .replace(LEADING_CONNECTOR, "")
            .replace(TRAILING_FILLER, "")
            .trim()
            .trimEnd('.', '!')
            .trim()
}
