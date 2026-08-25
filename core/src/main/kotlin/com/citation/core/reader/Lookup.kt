package com.citation.core.reader

/**
 * Looking a word up without leaving the sentence.
 *
 * The reason this belongs in `:core` rather than being three lines of intent-building in the UI is
 * that the hard part is not launching a dictionary — it is deciding **what** to look up. A reader
 * double-taps a word and the selection comes back as `“Whither,` or `mansions.` or a whole clause
 * they dragged across by accident. Handing any of those to a dictionary returns nothing, and "no
 * definition found" reads as the feature being broken rather than the query being wrong.
 *
 * So the term is cleaned first, and the *kind* of lookup is decided from what was selected: one word
 * is a definition, a phrase is not.
 */
object Lookup {

    /** What a selection is worth looking up as. */
    enum class Kind {
        /** A single word — a dictionary will have an entry for it. */
        WORD,

        /** A phrase — worth translating or searching, but not worth asking a dictionary about. */
        PHRASE,

        /** Nothing usable was selected. */
        NONE
    }

    /**
     * A cleaned-up query.
     *
     * @property term what to actually look up.
     * @property kind what it is worth looking up as.
     * @property truncated whether a long selection was cut down to a searchable length.
     */
    data class Query(val term: String, val kind: Kind, val truncated: Boolean = false) {
        val isEmpty: Boolean get() = kind == Kind.NONE
    }

    /** Past this, a "selection" is a paragraph and searching it verbatim finds nothing. */
    const val MAX_TERM_LENGTH = 120

    /**
     * Reduce a raw selection to something worth looking up.
     *
     * Leading and trailing punctuation goes — including the typographic quotes and dashes that a
     * book is full of and a plain `trim()` leaves behind. Internal punctuation stays, because
     * `don't`, `well-being` and `Mr.` are the word.
     */
    fun of(selection: String): Query {
        val collapsed = selection.replace('\n', ' ').trim().replace(Regex("\\s+"), " ")
        val cleaned = trimPunctuation(collapsed)
        if (cleaned.isEmpty()) return Query("", Kind.NONE)

        val truncated = cleaned.length > MAX_TERM_LENGTH
        val term = if (truncated) {
            val cut = cleaned.take(MAX_TERM_LENGTH)
            trimPunctuation(cut.substringBeforeLast(' ', cut))
        } else {
            cleaned
        }
        if (term.isEmpty()) return Query("", Kind.NONE)

        val words = term.split(' ').filter { it.isNotBlank() }
        return Query(
            term = term,
            kind = if (words.size == 1) Kind.WORD else Kind.PHRASE,
            truncated = truncated
        )
    }

    /** Strip punctuation and quote marks from both ends, keeping what is inside the word. */
    private fun trimPunctuation(value: String): String =
        value.trim { c -> !c.isLetterOrDigit() && c != '\'' && c != '’' && c != '-' }
            // A leading apostrophe or hyphen is punctuation too; one inside a word is not.
            .trim { c -> c == '-' }

    /**
     * Where to send a lookup when the device itself cannot answer it.
     *
     * Android's own dictionary action is the right first choice — it is what the platform's own
     * text selection offers, it respects the user's installed dictionary, and it works offline if
     * that dictionary does. But plenty of devices have no handler for it at all, and a menu item
     * that silently does nothing is worse than one that opens a web page. These are the fallbacks,
     * in the order worth trying.
     */
    fun webUrl(query: Query, target: Target = Target.DICTIONARY): String? {
        if (query.isEmpty) return null
        val encoded = encode(query.term)
        return when (target) {
            Target.DICTIONARY -> "https://en.wiktionary.org/wiki/${encode(query.term.lowercase())}"
            Target.ENCYCLOPEDIA -> "https://en.wikipedia.org/wiki/Special:Search?search=$encoded"
            Target.SEARCH -> "https://duckduckgo.com/?q=$encoded"
        }
    }

    /** The web fallbacks, in the order a reader would want them. */
    enum class Target {
        /** A dictionary entry for a word. */
        DICTIONARY,

        /** An encyclopedia article — for a name or a thing rather than a word. */
        ENCYCLOPEDIA,

        /** A plain web search — the catch-all that always resolves to something. */
        SEARCH
    }

    /**
     * Which web targets make sense for a query. A phrase gets no dictionary entry, so offering one
     * would be offering a dead end.
     */
    fun targetsFor(query: Query): List<Target> = when (query.kind) {
        Kind.WORD -> listOf(Target.DICTIONARY, Target.ENCYCLOPEDIA, Target.SEARCH)
        Kind.PHRASE -> listOf(Target.SEARCH, Target.ENCYCLOPEDIA)
        Kind.NONE -> emptyList()
    }

    private fun encode(value: String): String =
        try {
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            value.replace(" ", "%20")
        }
}
