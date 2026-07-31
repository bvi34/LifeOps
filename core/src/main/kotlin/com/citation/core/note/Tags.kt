package com.citation.core.note

/** A tag and how many notes carry it — the unit of the tag facet shown above the notes list. */
data class TagCount(val tag: String, val count: Int)

/**
 * The tag layer: parsing raw user input into clean labels, and indexing tags across the corpus.
 *
 * Tags follow the hashtag convention — single tokens, separated by spaces or commas, an optional
 * leading `#`, case-folded so `#Stoicism` and `stoicism` are one tag. A multi-word concept is a
 * hyphenated single tag (`#deep-work`), never two. Everything is normalized on the way in, so
 * storage, search, and the facet all agree on what a tag *is*.
 *
 * This is a **local organizational layer**: tags never leave on the sync wire (see [Note.tags]).
 */
object Tags {

    /** Fold one raw token to its canonical form: trimmed, no leading `#`, lowercase. */
    fun normalize(token: String): String =
        token.trim().removePrefix("#").trim().lowercase()

    /**
     * Turn a raw editor string into a clean, de-duplicated tag list. Commas, newlines, and runs of
     * whitespace all separate; blanks are dropped; first-seen order is kept so the editor is stable.
     */
    fun parse(raw: String): List<String> {
        val seen = LinkedHashSet<String>()
        raw.split(',', '\n', ' ', '\t').forEach { token ->
            val t = normalize(token)
            if (t.isNotEmpty()) seen.add(t)
        }
        return seen.toList()
    }

    /** Render a tag list back into an editable string (`#a #b #c`). */
    fun format(tags: List<String>): String = tags.joinToString(" ") { "#$it" }

    /** Every tag across [notes] with its note-count, most-used first then alphabetical. */
    fun counts(notes: List<Note>): List<TagCount> =
        notes.flatMap { it.tags }
            .groupingBy { it }.eachCount()
            .map { (tag, n) -> TagCount(tag, n) }
            .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag })

    /** The notes carrying [tag] (normalized exact match), input order preserved. */
    fun withTag(notes: List<Note>, tag: String): List<Note> {
        val t = normalize(tag)
        return notes.filter { note -> note.tags.any { it == t } }
    }
}
