package com.citation.core.reader

import com.citation.core.model.Book

/**
 * Finding a passage again inside the book you are reading.
 *
 * This is the most-missed feature in any reader, and it is worth being precise about what "found"
 * means. Searching a book is not searching a database: the text you remember is rarely the text on
 * the page. You remember "the clocks were striking" and the page says "the clocks were **striking
 * thirteen**"; you remember a phrase that the source happens to break across a line; you type it
 * lower-case. So matching normalises whitespace and case, and reports **where in the real text** the
 * hit landed — not where in some normalised copy — because the offsets have to be usable as a jump
 * target and as a highlight range in the chapter's canonical text.
 *
 * Everything here is pure and works on the in-memory [Book] the reader already holds. One person's
 * book is small; there is no index to build, no query round trip, and it stays offline-first like
 * the rest of Citation.
 */
object BookSearch {

    /**
     * One hit.
     *
     * @property chapterOrdinal which chapter it is in.
     * @property chapterTitle the chapter's title, so a result reads as a place in the book.
     * @property range the match in that chapter's **canonical** text — a jump target and a highlight
     *   range in one.
     * @property snippet a readable line of context around the match.
     * @property snippetMatch where within [snippet] the matched words sit, for emboldening them.
     */
    data class Hit(
        val chapterOrdinal: Int,
        val chapterTitle: String,
        val range: IntRange,
        val snippet: String,
        val snippetMatch: IntRange
    ) {
        /** Where to land when this hit is tapped. */
        val offset: Int get() = range.first
    }

    /** The shortest query worth running: a single letter matches everything and helps nobody. */
    const val MIN_QUERY = 2

    /** How many hits to return before giving up — past this, the query is the problem. */
    const val DEFAULT_LIMIT = 200

    /**
     * Search every chapter of [book] for [query].
     *
     * Results are in reading order, which is the order a reader thinks in. A chapter with no stored
     * text — a serial chapter not yet fetched — simply contributes nothing rather than being
     * reported as empty, because "not here" and "not downloaded yet" are different claims and only
     * one of them is true.
     */
    fun search(book: Book, query: String, limit: Int = DEFAULT_LIMIT): List<Hit> {
        val needle = normalize(query)
        if (needle.length < MIN_QUERY) return emptyList()

        val hits = ArrayList<Hit>()
        for (chapter in book.chapters) {
            if (chapter.text.isBlank()) continue
            findIn(chapter.text, needle).forEach { range ->
                if (hits.size >= limit) return hits
                val snippet = snippetOf(chapter.text, range)
                hits.add(
                    Hit(
                        chapterOrdinal = chapter.ordinal,
                        chapterTitle = chapter.title,
                        range = range,
                        snippet = snippet.text,
                        snippetMatch = snippet.match
                    )
                )
            }
        }
        return hits
    }

    /** How many chapters hold at least one hit — the "in 7 chapters" line above a result list. */
    fun chaptersWithHits(hits: List<Hit>): Int = hits.map { it.chapterOrdinal }.distinct().size

    /**
     * Every match of [needle] in [text], as ranges into [text] itself.
     *
     * The walk compares character by character against a **normalised** view of the text without
     * ever materialising one: runs of whitespace in the text collapse to a single space, and case is
     * folded, while the returned range still refers to the original offsets. Building a normalised
     * copy and mapping back would work too, but this way there is only one set of offsets in
     * existence and therefore no chance of returning the wrong one.
     */
    fun findIn(text: String, needle: String): List<IntRange> {
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var start = 0
        while (start < text.length) {
            val end = matchAt(text, start, needle)
            if (end > start) {
                out.add(start until end)
                // Continue past this hit; overlapping matches of the same phrase are noise.
                start = end
            } else {
                start++
            }
        }
        return out
    }

    /**
     * If [needle] matches [text] beginning at [from], the exclusive end offset in [text]; else
     * [from]. Both sides are compared in the same normalised terms.
     */
    private fun matchAt(text: String, from: Int, needle: String): Int {
        var t = from
        var n = 0
        while (n < needle.length) {
            if (t >= text.length) return from
            val wanted = needle[n]
            if (wanted == ' ') {
                if (!text[t].isWhitespace()) return from
                while (t < text.length && text[t].isWhitespace()) t++
                n++
                continue
            }
            val here = text[t]
            if (here.isWhitespace() || here.lowercaseChar() != wanted) return from
            t++
            n++
        }
        return t
    }

    /** Query text reduced to the terms matching is done in: folded case, single spaces, trimmed. */
    fun normalize(query: String): String {
        val sb = StringBuilder(query.length)
        var space = false
        query.forEach { c ->
            if (c.isWhitespace()) {
                if (sb.isNotEmpty()) space = true
            } else {
                if (space) sb.append(' ')
                space = false
                sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /** A snippet plus where the match sits inside it. */
    data class Snippet(val text: String, val match: IntRange)

    /**
     * A readable line of context around [range].
     *
     * Cut at word boundaries rather than mid-word, and elide with a real ellipsis so a clipped
     * snippet is visibly clipped. Newlines inside the window become spaces — a result row is one
     * line, and a paragraph break rendered as a gap in a list looks like a bug.
     */
    fun snippetOf(text: String, range: IntRange, context: Int = 48): Snippet {
        val matchStart = range.first.coerceIn(0, text.length)
        val matchEnd = (range.last + 1).coerceIn(matchStart, text.length)

        var from = (matchStart - context).coerceAtLeast(0)
        var to = (matchEnd + context).coerceAtMost(text.length)
        if (from > 0) {
            val boundary = text.indexOf(' ', from)
            if (boundary in from until matchStart) from = boundary + 1
        }
        if (to < text.length) {
            val boundary = text.lastIndexOf(' ', to)
            if (boundary in matchEnd until to) to = boundary
        }

        val prefix = if (from > 0) ELLIPSIS else ""
        val suffix = if (to < text.length) ELLIPSIS else ""
        val body = text.substring(from, to).replace('\n', ' ').trim()

        // Trimming can eat leading whitespace, which would shift the match; measure from the body.
        val shift = text.substring(from, to).indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val start = prefix.length + (matchStart - from - shift).coerceAtLeast(0)
        val end = (start + (matchEnd - matchStart)).coerceAtMost(prefix.length + body.length)

        return Snippet(
            text = prefix + body + suffix,
            match = start until maxOf(end, start)
        )
    }

    private const val ELLIPSIS = "…"
}
