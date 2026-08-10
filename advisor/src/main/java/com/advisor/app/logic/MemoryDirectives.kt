package com.advisor.app.logic

/** A parsed request from the model to save [content] to long-term memory under [tags]. */
data class MemoryWrite(val content: String, val tags: List<String> = emptyList())

/**
 * The model's write-capability seam for **long-term memory** — the memory analogue of
 * [ProfileDirectives]. So the assistant can *add to* memory (not just recall from it), it may emit
 * lines of the form:
 *
 * ```
 * @memorize: the user's dog is named Rex #pets #family
 * @memorize: prefers meetings after 10am
 * ```
 *
 * Inline `#hashtags` become the memory's recall tags (they're stripped out of the stored content);
 * everything else on the line is the fact. [parse] pulls the directives out of a model reply and
 * [strip] removes them from the text shown to the user, so the repository can apply the writes and
 * display a clean answer. Pure and testable; a real model just follows the convention (the system
 * prompt tells it to), and a future function-calling model can emit [MemoryWrite]s directly.
 */
object MemoryDirectives {

    /** Guidance handed to the model so it knows the convention. */
    const val INSTRUCTION: String =
        "To save a durable fact to long-term memory, add a line: @memorize: <fact> #tag1 #tag2 " +
            "(tags are optional; use #hashtags for recall)."

    // @memorize: <text with optional #tags>  — case-insensitive, one per line.
    private val DIRECTIVE = Regex("""(?im)^[ \t]*@memorize\s*:\s*(.+?)[ \t]*$""")

    // A #tag: a leading letter/digit then word-ish characters. Captured without the leading '#'.
    private val TAG = Regex("""#([A-Za-z0-9][A-Za-z0-9_-]*)""")

    fun parse(text: String): List<MemoryWrite> =
        DIRECTIVE.findAll(text)
            .map { toWrite(it.groupValues[1]) }
            .filter { it.content.isNotBlank() }
            .toList()

    /** The reply with directive lines removed and surrounding blank lines tidied. */
    fun strip(text: String): String =
        text.lineSequence()
            .filterNot { DIRECTIVE.matches(it) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Split a raw directive body into its fact and its (normalised, de-duped) tags. */
    fun toWrite(raw: String): MemoryWrite {
        val tags = TAG.findAll(raw)
            .map { it.groupValues[1].lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val content = raw.replace(TAG, " ").replace(Regex("\\s{2,}"), " ").trim()
        return MemoryWrite(content, tags)
    }
}
