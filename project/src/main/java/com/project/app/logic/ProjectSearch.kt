package com.project.app.logic

/** Which section a hit came from. The order here is the order results are grouped in. */
enum class SearchSection(val key: String, val label: String) {
    OUTLINE("outline", "Outline"),
    DOCS("docs", "Docs"),
    LORE("lore", "Lore"),
    TIMELINE("timeline", "Timeline"),
    BOARD("board", "Board")
}

/** One result. [parentId] is set where opening the hit means opening something else — a doc's id. */
data class SearchHit(
    val section: SearchSection,
    val id: String,
    val parentId: String?,
    val title: String,
    /** The matching text in context, or null when the title itself was the match. */
    val snippet: String?,
    /** True when the query matched the title rather than the body. Ranked first. */
    val titleMatch: Boolean
)

/** A document flattened for searching — its blocks joined into one string by the caller. */
data class SearchDoc(val id: String, val title: String, val text: String)

/** Everything in a project that has text in it — one load, matched against many queries. */
data class SearchCorpus(
    val outline: List<OutlineNode>,
    val docs: List<SearchDoc>,
    val lore: List<LoreEntry>,
    val events: List<TimelineEvent>,
    val cards: List<BoardCard>
)

/**
 * Search across everything in one project.
 *
 * Five sections hold text and, until this existed, only one of them (Lore) could be searched — which
 * meant that past about forty documents the repository stopped being a repository and became a pile.
 *
 * The matching is deliberately plain: **case-insensitive substring, all terms required.** No
 * stemming, no fuzzy matching, no ranking by term frequency. For a corpus of one person's own
 * project that is not a compromise — you are looking for a name or a phrase you wrote yourself, and
 * you can spell it. Fuzzy matching over a small corpus mostly produces confident wrong answers, and
 * a search that returns the scene you did not mean is worse than one that returns nothing and lets
 * you retype.
 *
 * Ranking is title-before-body, then section order, then alphabetical — fully deterministic, so the
 * same query always produces the same list in the same order.
 */
object ProjectSearch {

    private val WHITESPACE = Regex("""\s+""")

    /** The query as terms. Empty when there is nothing to search for. */
    fun terms(query: String): List<String> =
        WHITESPACE.split(query.trim().lowercase()).filter { it.isNotEmpty() }

    /** True when every term appears somewhere in [text]. Terms may land anywhere, in any order. */
    fun matches(text: String?, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val haystack = text?.lowercase() ?: return false
        return terms.all { haystack.contains(it) }
    }

    /**
     * The matching text in context: the first term's neighbourhood, with ellipses where it was cut.
     *
     * Whitespace is collapsed first, so a snippet from a document whose blocks were joined by
     * newlines reads as one line rather than as a ragged column.
     */
    fun snippet(text: String, terms: List<String>, radius: Int = 48): String? {
        val flat = WHITESPACE.replace(text, " ").trim()
        if (flat.isEmpty()) return null
        val lower = flat.lowercase()
        val at = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null

        val start = (at - radius).coerceAtLeast(0)
        val end = (at + radius).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append("…")
            append(flat.substring(start, end).trim())
            if (end < flat.length) append("…")
        }
    }

    /**
     * Everything in the project that matches [query].
     *
     * An empty query returns nothing rather than everything: a search screen that dumps the whole
     * project before you have typed is a list, not an answer.
     */
    fun search(
        query: String,
        outline: List<OutlineNode>,
        docs: List<SearchDoc>,
        lore: List<LoreEntry>,
        events: List<TimelineEvent>,
        cards: List<BoardCard>
    ): List<SearchHit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()

        val hits = ArrayList<SearchHit>()

        outline.forEach { node ->
            hits += hitFor(SearchSection.OUTLINE, node.id, null, node.title, node.synopsis.orEmpty(), terms)
        }
        docs.forEach { doc ->
            // A doc hit opens the doc, so it carries its own id as the thing to open.
            hits += hitFor(SearchSection.DOCS, doc.id, doc.id, doc.title, doc.text, terms)
        }
        lore.forEach { entry ->
            hits += hitFor(
                section = SearchSection.LORE,
                id = entry.id,
                parentId = null,
                // Aliases count as naming the entry: searching "Kes" should rank her by name.
                title = (listOf(entry.name) + entry.aliases).joinToString(" "),
                body = listOfNotNull(entry.summary, entry.body).joinToString(" "),
                terms = terms,
                display = entry.name
            )
        }
        events.forEach { event ->
            hits += hitFor(
                section = SearchSection.TIMELINE,
                id = event.id,
                parentId = null,
                title = event.title,
                body = listOfNotNull(event.detail, event.whenLabel, event.era).joinToString(" "),
                terms = terms
            )
        }
        cards.forEach { card ->
            hits += hitFor(SearchSection.BOARD, card.id, null, card.title, card.notes.orEmpty(), terms)
        }

        return hits.sortedWith(
            compareBy(
                { !it.titleMatch },
                { it.section.ordinal },
                { it.title.lowercase() },
                { it.id }
            )
        )
    }

    /**
     * One record, matched.
     *
     * The terms are tested against **the whole record**, title and body together — not against each
     * half separately. That distinction is the difference between a search that works and one that
     * doesn't: "kestrel smuggler" has the name in the title and the description in the body, and
     * requiring every term to land on the same side would find nothing while the record sits there
     * containing both words.
     *
     * [titleMatch] is a narrower question, used only for ranking: does the title *alone* carry every
     * term? Something you named after the thing you searched for belongs above something that merely
     * mentions it.
     *
     * [display] is the title as it should be shown, where that differs from the text searched — a
     * lore entry is searched across its aliases but shown under its own name.
     */
    private fun hitFor(
        section: SearchSection,
        id: String,
        parentId: String?,
        title: String,
        body: String,
        terms: List<String>,
        display: String = title
    ): List<SearchHit> {
        if (!matches("$title $body", terms)) return emptyList()
        val inTitle = matches(title, terms)
        return listOf(
            SearchHit(
                section = section,
                id = id,
                parentId = parentId,
                title = display,
                snippet = if (inTitle) null else snippet(body, terms),
                titleMatch = inTitle
            )
        )
    }

    /** Hits grouped for the screen, sections with nothing dropped. */
    fun grouped(hits: List<SearchHit>): List<Pair<SearchSection, List<SearchHit>>> =
        SearchSection.entries
            .map { section -> section to hits.filter { it.section == section } }
            .filter { (_, found) -> found.isNotEmpty() }
}
