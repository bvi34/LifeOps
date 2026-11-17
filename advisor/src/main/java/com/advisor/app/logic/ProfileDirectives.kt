package com.advisor.app.logic

/** A parsed request from the model to append [text] to the profile addressed by [profileKey]. */
data class ProfileAppend(val profileKey: String, val text: String)

/**
 * The model's write-capability seam for standing profiles. So the assistant can *add to* a profile
 * (not just read it), it may emit lines of the form:
 *
 * ```
 * @remember(project-a): shipped the v2 build today
 * @remember(llm-persona): the user prefers terse answers
 * ```
 *
 * [parse] pulls those directives out of a model reply and [strip] removes them from the text shown to
 * the user, so the repository can apply the writes and display a clean answer. Pure and testable; the
 * real model just needs to follow the convention (the system prompt tells it to). A future
 * function-calling model could bypass this and emit structured [ProfileAppend]s directly.
 */
object ProfileDirectives {

    /** Guidance handed to the model so it knows the convention. */
    const val INSTRUCTION: String =
        "To save a durable fact to a standing profile, add a line: @remember(<profile>): <fact>. " +
            "Use an existing profile key (e.g. user, llm-persona) or a new project key."

    // @remember(<key>): <text>  — case-insensitive, one per line.
    private val DIRECTIVE = Regex("""(?im)^[ \t]*@remember\(\s*([A-Za-z0-9][A-Za-z0-9 _-]*?)\s*\)\s*:\s*(.+?)[ \t]*$""")

    fun parse(text: String): List<ProfileAppend> =
        DIRECTIVE.findAll(text)
            .map { ProfileAppend(slug(it.groupValues[1]), it.groupValues[2].trim()) }
            .filter { it.profileKey.isNotBlank() && it.text.isNotBlank() }
            .toList()

    /**
     * The reply with directive lines removed and surrounding blank lines tidied.
     *
     * A directive the model wrote *mid-sentence* ("I see you are Brenden. @remember(User): …") is not
     * applied — [parse] only honours one on its own line — but it is still the app's bookkeeping, not
     * prose, so it is cut from the `@remember(` to the end of that line rather than shown to the user.
     */
    fun strip(text: String): String =
        text.lineSequence()
            .filterNot { DIRECTIVE.matches(it) }
            .map { line -> INLINE.find(line)?.let { line.substring(0, it.range.first).trimEnd() } ?: line }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private val INLINE = Regex("""(?i)@remember\s*\(""")

    /**
     * Whether [append] adds something [profile] doesn't already hold. A small model given a profile
     * in its context tends to "save" what it was just shown — the user's own name, or the profile's
     * description read back as a fact — so each answer would grow the profile by a copy of itself.
     * Rejected: text already contained in an existing entry or in [known] (identity lines and the
     * like), and text that repeats the profile's own summary.
     */
    fun isNew(append: ProfileAppend, profile: Profile?, known: List<String> = emptyList()): Boolean {
        val text = comparable(append.text)
        if (text.length < 3) return false
        val held = buildList {
            addAll(known)
            profile?.entries?.forEach { add(it.text) }
        }.map(::comparable)
        if (held.any { it.contains(text) }) return false
        val summary = profile?.summary?.let(::comparable).orEmpty()
        if (summary.length >= 12 && (text.contains(summary) || summary.contains(text))) return false
        return true
    }

    private fun comparable(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** Normalise a free-form profile name to its addressable key: lower-kebab, safe characters. */
    fun slug(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
}
