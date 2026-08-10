package com.advisor.app.logic

/**
 * What kind of standing dossier a [Profile] is. Only a hint for grouping/labelling — the mechanism is
 * the same for all of them.
 */
enum class ProfileKind(val key: String) {
    USER("user"),
    PERSONA("persona"),
    PROJECT("project"),
    OTHER("other");

    companion object {
        fun fromKey(key: String): ProfileKind = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** One appended line in a profile, tagged with who wrote it (the user or the assistant). */
data class ProfileEntry(
    val text: String,
    val author: String = AUTHOR_USER,
    val createdAt: Long = 0L
) {
    companion object {
        const val AUTHOR_USER = "user"
        const val AUTHOR_ADVISOR = "advisor"
    }
}

/**
 * A **standing profile** — a long-lived, named dossier the assistant always has in context and can
 * reference by name (`user`, `llm-persona`, `project-a`, …). Unlike long-term memory, profiles are
 * *not* retrieved from a database per question: they're a small, always-injected set (persisted as
 * JSON, like identity), so referencing "Project A" never costs a query. Both the user and the model
 * can append to them — the model via the `@remember(<key>): …` directive (see [ProfileDirectives]).
 *
 * [key] is the stable slug used to address the profile (in the prompt and in write directives);
 * [name] is the human label. [alwaysInclude] lets a profile be kept on disk but left out of the
 * always-on context when it isn't currently relevant.
 */
data class Profile(
    val key: String,
    val name: String,
    val kind: ProfileKind = ProfileKind.OTHER,
    val summary: String = "",
    val entries: List<ProfileEntry> = emptyList(),
    val alwaysInclude: Boolean = true,
    val updatedAt: Long = 0L
) {

    /** The most recent [max] entries, oldest-first, as they should read in context. */
    fun recentEntries(max: Int = DEFAULT_MAX_ENTRIES): List<ProfileEntry> =
        if (entries.size <= max) entries else entries.subList(entries.size - max, entries.size)

    /** A new profile with [text] appended by [author]. */
    fun appended(text: String, author: String, now: Long): Profile =
        copy(entries = entries + ProfileEntry(text.trim(), author, now), updatedAt = now)

    /** A new profile with entries matching [text] removed, plus the number removed. */
    fun withoutMatching(text: String, now: Long): Pair<Profile, Int> {
        val target = comparable(text)
        val kept = entries.filterNot { comparable(it.text) == target }
        return copy(entries = kept, updatedAt = if (kept.size == entries.size) updatedAt else now) to
            (entries.size - kept.size)
    }

    /** The header line for this profile: name, its addressable key, and summary. */
    fun headerLine(): String = buildString {
        append(name)
        if (!name.equals(key, ignoreCase = true)) append(" [").append(key).append(']')
        if (summary.isNotBlank()) append(" — ").append(summary)
    }

    /** Header + recent entries, for the prompt's PROFILES section and the UI. */
    fun toContextLines(max: Int = DEFAULT_MAX_ENTRIES): List<String> {
        val lines = ArrayList<String>()
        lines += headerLine()
        for (entry in recentEntries(max)) lines += "• " + entry.text
        return lines
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 8

        private fun comparable(text: String): String =
            text.trim().trimEnd('.', '!').lowercase().replace(Regex("""\s+"""), " ")
    }
}
