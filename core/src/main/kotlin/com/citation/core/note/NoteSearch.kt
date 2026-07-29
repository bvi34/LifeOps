package com.citation.core.note

/**
 * In-memory full-text search over captured notes. The whole note corpus is small (one person's
 * reading) and already loaded, so this is a pure filter over a `List<Note>` — no FTS table, no
 * query round-trip, works offline like everything else here.
 *
 * A note matches a query when **every** whitespace-separated token appears (as a substring,
 * case-insensitively) somewhere in the note's searchable surface: your written body, the frozen
 * quoted snapshot(s), the source title/author, and its tags. AND-of-tokens keeps multi-word
 * queries narrowing rather than widening, which is what you want when hunting one note in a pile.
 */
object NoteSearch {

    /** [notes] matching [query], input order preserved. A blank query returns [notes] unchanged. */
    fun match(notes: List<Note>, query: String): List<Note> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return notes
        return notes.filter { note ->
            val hay = haystack(note)
            tokens.all { hay.contains(it) }
        }
    }

    /** Everything about a note that is searchable, lowercased and newline-joined. */
    private fun haystack(note: Note): String = buildString {
        append(note.body.lowercase()).append('\n')
        append(note.source.title.lowercase()).append('\n')
        note.source.author?.let { append(it.lowercase()).append('\n') }
        note.references.forEach { append(it.quotedSnapshot.lowercase()).append('\n') }
        note.tags.forEach { append(it.lowercase()).append('\n') }
    }

    private fun tokenize(query: String): List<String> =
        query.lowercase().split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
}
