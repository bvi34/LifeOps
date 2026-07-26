package com.citation.core.kindle

import com.citation.core.capture.CaptureProvenance
import com.citation.core.capture.ProvenanceLadder
import com.citation.core.capture.RawCapture
import com.citation.core.epub.Html
import com.citation.core.identity.IdentityKey

/**
 * Parses the HTML **notebook export** Kindle produces (the "Export notebook" action, synced from your
 * Amazon account) into capture-ready records.
 *
 * Kindle is a **highlight-*import*** source, not a live one, and deliberately so. Its book pages are a
 * canvas behind a secure-window flag — the text isn't in the accessibility tree and a screen grab
 * comes back black — so reading the page under Kindle is blocked by design and would cross the DRM
 * line. What Amazon *does* hand you is this export: the highlights and notes you made normally, after
 * the fact. So the contract here is honest about its limits — it is **non-realtime** and subject to
 * Amazon's **per-book clipping/export limit** (a truncated export is a truncated export; the parser
 * ingests whatever the file contains and no more).
 *
 * Once parsed, a Kindle highlight is *just another capture*: a note packet with a source. The export
 * rarely carries a machine identity, so a book clusters on its title and waits for
 * [com.citation.core.capture.CapturePromotion] to bind it when you add the book properly — the exact
 * same lifecycle as any other provisional capture.
 *
 * The parse is intentionally tolerant of the export's cosmetic drift (class-name suffixes, optional
 * page numbers, books with or without section headings) and dependency-free, reusing [Html] for the
 * same deterministic text reduction the reader uses everywhere else.
 */
object KindleNotebook {

    /** The book an export is for. [identity] is usually absent — the notebook rarely names an ASIN/ISBN. */
    data class Book(val title: String, val author: String?, val identity: IdentityKey? = null)

    /** One exported item: a highlighted [quote] and, when you wrote one, its [annotation]. */
    data class Entry(
        val quote: String,
        val annotation: String?,
        val location: String?,
        val page: String?,
        val chapter: String?
    ) {
        /** A quote-less entry is a standalone Kindle note (a thought with no passage attached). */
        val isStandaloneNote: Boolean get() = quote.isBlank() && !annotation.isNullOrBlank()

        /** The opaque position token for the note's anchor: the location, else the page. */
        val locationToken: String? get() = location?.let { "Location $it" } ?: page?.let { "Page $it" }
    }

    /** A parsed export: the book plus its entries in document order. */
    data class Export(val book: Book, val entries: List<Entry>) {
        /**
         * The provenance every entry in this export shares. A machine identity wins the top ladder
         * rung; otherwise the book's title carries it (a provisional cluster, promotable later).
         */
        fun provenance(capturedAt: Long): CaptureProvenance =
            ProvenanceLadder.resolve(
                RawCapture(
                    text = book.title,
                    bookIdentity = book.identity,
                    title = book.title,
                    author = book.author,
                    capturedAt = capturedAt
                )
            )
    }

    private val BOOK_TITLE = classedBlock("bookTitle")
    private val AUTHORS = classedBlock("authors")
    // Every heading/text block, in order, tagged by which of the two it is.
    private val NOTE_BLOCK = Regex(
        "(?is)<(?:h\\d|div|p)[^>]*class=\"[^\"]*\\b(noteHeading|noteText)\\b[^\"]*\"[^>]*>(.*?)</(?:h\\d|div|p)>"
    )
    private val LOCATION = Regex("(?i)Location\\s+([0-9]+(?:-[0-9]+)?)")
    private val PAGE = Regex("(?i)Page\\s+([0-9A-Za-z]+(?:-[0-9A-Za-z]+)?)")

    /**
     * Parse a notebook export. Returns `null` when the HTML has no book title or yields no entries —
     * i.e. it isn't a Kindle notebook — so a caller can cleanly reject the wrong file.
     */
    fun parse(html: String): Export? {
        val title = textOf(BOOK_TITLE.find(html)?.groupValues?.get(1)) ?: return null
        val author = textOf(AUTHORS.find(html)?.groupValues?.get(1))
        val entries = parseEntries(html)
        if (entries.isEmpty()) return null
        return Export(Book(title, author), entries)
    }

    private fun parseEntries(html: String): List<Entry> {
        // Flatten the document into an ordered (kind, text) stream, then fold headings onto their text.
        data class Block(val kind: String, val heading: String, val text: String)
        val blocks = ArrayList<Block>()
        var pendingHeading: String? = null
        for (m in NOTE_BLOCK.findAll(html)) {
            val kind = m.groupValues[1]
            val body = textOf(m.groupValues[2]).orEmpty()
            if (kind == "noteHeading") {
                pendingHeading = body
            } else { // noteText — always belongs to the most recent heading
                blocks.add(Block("noteText", pendingHeading.orEmpty(), body))
                pendingHeading = null
            }
        }

        val entries = ArrayList<Entry>()
        for (block in blocks) {
            val heading = block.heading
            val isNote = heading.trimStart().startsWith("Note", ignoreCase = true)
            val location = LOCATION.find(heading)?.groupValues?.get(1)
            val page = PAGE.find(heading)?.groupValues?.get(1)
            val chapter = chapterOf(heading)
            if (isNote) {
                // A Note at the same location as the previous highlight is that highlight's annotation.
                val prev = entries.lastOrNull()
                if (prev != null && prev.annotation == null && sameSpot(prev, location, page)) {
                    entries[entries.lastIndex] = prev.copy(annotation = block.text)
                } else {
                    entries.add(Entry("", block.text, location, page, chapter))
                }
            } else {
                entries.add(Entry(block.text, null, location, page, chapter))
            }
        }
        return entries
    }

    private fun sameSpot(entry: Entry, location: String?, page: String?): Boolean =
        (location != null && location == entry.location) || (page != null && page == entry.page) ||
            (location == null && page == null)

    /** The chapter/section name in a heading: the text between the first " - " and the next " > "/" · ". */
    private fun chapterOf(heading: String): String? {
        val afterDash = heading.substringAfter(" - ", "").ifBlank { return null }
        val cut = afterDash.indexOfFirst { it == '>' || it == '·' }
        val chapter = (if (cut >= 0) afterDash.substring(0, cut) else afterDash).trim().trimEnd('>')
        return chapter.trim().takeIf { it.isNotBlank() && !it.startsWith("Location") && !it.startsWith("Page") }
    }

    private fun classedBlock(cls: String) =
        Regex("(?is)<(?:div|h\\d|p)[^>]*class=\"[^\"]*\\b$cls\\b[^\"]*\"[^>]*>(.*?)</(?:div|h\\d|p)>")

    private fun textOf(rawHtml: String?): String? =
        rawHtml?.let { Html.toText(it) }?.trim()?.takeIf { it.isNotBlank() }
}
