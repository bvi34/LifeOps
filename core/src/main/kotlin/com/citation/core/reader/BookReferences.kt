package com.citation.core.reader

import com.citation.core.doc.DocumentBlock
import com.citation.core.model.Book
import com.citation.core.model.Chapter

/**
 * Where a link or a note reference in a book's text points.
 *
 * Two answers rather than a URL, because the reader does two entirely different things with them: a
 * place in this book is a jump it can offer to undo, and anything else leaves the book altogether
 * and is worth asking about first.
 */
sealed interface ReferenceTarget {

    /** A place in the book being read: the chapter, and where in its canonical text. */
    data class InBook(val chapterOrdinal: Int, val offset: Int) : ReferenceTarget

    /** Somewhere outside it — the web, a mail address, another app. */
    data class External(val url: String) : ReferenceTarget
}

/**
 * Following a link, or a note reference, that the text itself states.
 *
 * The producer has done the hard half already: `EpubParser` rewrites every inline `href` from
 * chapter-relative to zip-absolute, which is the same form a [Chapter.sourceRef] is in, and records
 * each `id` it passed as an offset into the canonical text ([Chapter.anchors]). So a reference only
 * has to be *read*, which is what this does — and it is worth doing here rather than in the reader
 * because the interesting decisions are decisions rather than drawing.
 *
 * The sharpest of them: a reference naming a fragment this book does not contain resolves to
 * **nothing**, not to the top of the file it names. Citation's rule everywhere else is that an
 * anchor which cannot be resolved degrades rather than jumping somewhere wrong, and a footnote is
 * where a wrong jump does the most damage — landing at the top of a notes document and showing note
 * 1 in answer to a tap on footnote 17 is not a degraded answer, it is a confident wrong one.
 */
object BookReferences {

    /**
     * Where [href] points, or `null` when it cannot be followed.
     *
     * [fromChapter] is the chapter the reference was stated in, which is what a bare `#fragment`
     * is relative to.
     */
    fun resolve(href: String?, book: Book, fromChapter: Int): ReferenceTarget? {
        val target = href?.trim().orEmpty()
        if (target.isEmpty()) return null
        // A scheme means it is somebody else's address, whatever else it looks like. Checked first
        // so `https://example.com/notes#fn1` is never mistaken for a document in this book.
        if (SCHEME.containsMatchIn(target)) return ReferenceTarget.External(target)

        val fragment = target.substringAfter('#', "").takeIf { it.isNotBlank() }
        val file = target.substringBefore('#').takeIf { it.isNotBlank() }
        val chapter = if (file == null) {
            book.chapterAt(fromChapter)
        } else {
            // The spine document this names. Absent when the link points at something outside the
            // spine — a cover page, a stylesheet, a file the producer dropped — which is a link the
            // reader cannot follow rather than one it should guess at.
            book.chapters.firstOrNull { it.sourceRef == file }
        } ?: return null

        if (fragment == null) return ReferenceTarget.InBook(chapter.ordinal, 0)
        val offset = chapter.anchors[fragment] ?: return null
        return ReferenceTarget.InBook(chapter.ordinal, offset)
    }

    /**
     * The note at [target], for showing without leaving the sentence that referred to it.
     *
     * A note is the block its anchor sits in — the `<li>` or `<p>` the producer hung the `id` on —
     * read from the anchor rather than from the block's start, so a note whose whole list is one
     * block still begins where it was pointed at. A book with no structure recovered falls back to
     * the paragraph, which the reduction has already marked off with a blank line.
     *
     * Cut at [limit] on a word boundary: this is a popup over a sentence somebody is in the middle
     * of, not a chapter. The reader offers to go to the note itself for the rest, which is also the
     * honest answer for the endnote that runs to a page.
     */
    fun note(book: Book, target: ReferenceTarget.InBook, limit: Int = NOTE_LIMIT): String? {
        val chapter = book.chapterAt(target.chapterOrdinal) ?: return null
        val from = target.offset.coerceIn(0, chapter.text.length)
        val end = noteEnd(chapter, from)
        val body = chapter.text.substring(from, end).trim()
        if (body.isEmpty()) return null
        return elide(body, limit)
    }

    /** Whether [target] has more to it than [note] showed. */
    fun noteIsCut(book: Book, target: ReferenceTarget.InBook, limit: Int = NOTE_LIMIT): Boolean {
        val chapter = book.chapterAt(target.chapterOrdinal) ?: return false
        val from = target.offset.coerceIn(0, chapter.text.length)
        return chapter.text.substring(from, noteEnd(chapter, from)).trim().length > limit
    }

    /** Where the note starting at [from] ends: its block, or the paragraph the reduction marked. */
    private fun noteEnd(chapter: Chapter, from: Int): Int {
        val block = chapter.blocks
            .filterIsInstance<DocumentBlock.Text>()
            .filter { from >= it.start && from < it.end }
            // The innermost, for a producer that nests a note inside a list inside a section.
            .minByOrNull { it.end - it.start }
        if (block != null) return block.end
        val paragraph = chapter.text.indexOf("\n\n", from)
        return if (paragraph < 0) chapter.text.length else paragraph
    }

    /** [text] cut to [limit] at a word boundary, with a visible elision. */
    private fun elide(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val cut = text.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
        return text.take(cut).trimEnd() + "…"
    }

    /** How much of a note a popup shows before it stops being a popup. */
    const val NOTE_LIMIT = 600

    /** `https:`, `mailto:`, `tel:` — anything stating a scheme is somebody else's address. */
    private val SCHEME = Regex("(?i)^[a-z][a-z0-9+.-]*:")
}
