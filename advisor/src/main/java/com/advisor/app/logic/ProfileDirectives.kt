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

    /** The reply with directive lines removed and surrounding blank lines tidied. */
    fun strip(text: String): String =
        text.lineSequence()
            .filterNot { DIRECTIVE.matches(it) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Normalise a free-form profile name to its addressable key: lower-kebab, safe characters. */
    fun slug(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
}
