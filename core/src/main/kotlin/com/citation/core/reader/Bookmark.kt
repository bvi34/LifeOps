package com.citation.core.reader

import com.citation.core.anchor.FuzzyAnchor
import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.Book

/**
 * A place you want to come back to.
 *
 * A bookmark is **a position, not a passage** — which is exactly what distinguishes it from a
 * highlight, and why the reader needed both. A highlight says "these words matter"; a bookmark says
 * "I was here". Conflating them makes one of the two lie: you end up either highlighting a sentence
 * you did not care about in order to mark your place, or scrolling a list of positions looking for
 * the one that was actually about something.
 *
 * But a bookmark that is only an offset is fragile in exactly the way an anchor-by-offset is
 * fragile: a re-fetched serial chapter, a re-exported EPUB, and the number points at the wrong
 * words. So a bookmark is durable the same way a note is — it freezes the line it was set on and
 * re-resolves through [FuzzyAnchor], landing where those words are *now*. When the passage is gone
 * entirely, the bookmark degrades to its chapter rather than jumping somewhere wrong.
 */
data class Bookmark(
    val key: EntityKey,
    /**
     * Null once the book has been removed. A bookmark is sovereign — it outlives its source the way
     * a note does, staying readable from its frozen line rather than being deleted along with it.
     */
    val bookKey: EntityKey?,
    val chapterOrdinal: Int,
    /** Where it was set, in the chapter text as it read then. A hint, not the truth. */
    val charOffset: Int,
    /**
     * The line it was set on, frozen. This is what makes the bookmark legible in a list without
     * loading the book, and what lets it be re-found after the text moves.
     */
    val snippet: String,
    /** The chapter's title when it was set, so the list reads as places in a book. */
    val chapterTitle: String,
    /** The reader's own label, when they gave one. */
    val label: String? = null,
    val createdAt: Long = 0
) {

    /** What to show in a list: the reader's label if they wrote one, otherwise the frozen line. */
    val display: String get() = label?.takeIf { it.isNotBlank() } ?: snippet

    /** The anchor this bookmark resolves through — the same machinery a note's passage uses. */
    fun anchor(): TextAnchor.Flowing = TextAnchor.Flowing(
        chapterOrdinal = chapterOrdinal,
        approxStart = charOffset,
        quote = snippet
    )
}

/** Turning a position into a bookmark, and a bookmark back into a position. */
object Bookmarks {

    /** How much of the line to freeze — enough to re-find it, short enough to read in a list. */
    const val SNIPPET_LENGTH = 90

    /**
     * Build a bookmark for a position in [book].
     *
     * The frozen snippet starts at the position and runs to the end of the sentence or the length
     * cap, whichever comes first — the words a reader would recognise as "where I was", rather than
     * a window centred on an arbitrary character.
     */
    fun at(
        key: EntityKey,
        bookKey: EntityKey,
        book: Book,
        chapterOrdinal: Int,
        charOffset: Int,
        label: String? = null,
        now: Long = 0
    ): Bookmark {
        val chapter = book.chapterAt(chapterOrdinal)
        val text = chapter?.text.orEmpty()
        return Bookmark(
            key = key,
            bookKey = bookKey,
            chapterOrdinal = chapterOrdinal,
            charOffset = charOffset.coerceIn(0, text.length),
            snippet = snippetAt(text, charOffset),
            chapterTitle = chapter?.title.orEmpty(),
            label = label?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now
        )
    }

    /** The line a bookmark freezes, given the chapter text and where it was set. */
    fun snippetAt(text: String, charOffset: Int, length: Int = SNIPPET_LENGTH): String {
        if (text.isEmpty()) return ""
        val start = charOffset.coerceIn(0, text.length)
        // Start at a word boundary so the snippet does not open mid-word.
        val from = if (start > 0 && !text[start].isWhitespace()) {
            val space = text.lastIndexOf(' ', start)
            if (space >= 0 && start - space <= 20) space + 1 else start
        } else {
            start
        }
        val window = text.substring(from, (from + length).coerceAtMost(text.length))
        val sentenceEnd = window.indexOfFirst { it == '.' || it == '!' || it == '?' }
        val cut = if (sentenceEnd in 30 until window.length) sentenceEnd + 1 else window.length
        return window.substring(0, cut).replace('\n', ' ').trim()
    }

    /** Where a bookmark points *now*. */
    sealed class Target {
        /** The frozen line was found; land on it. */
        data class Found(val chapterOrdinal: Int, val charOffset: Int, val exact: Boolean) : Target()

        /**
         * The line is gone — an edited chapter, a re-cut export. The chapter still exists, so the
         * bookmark opens it rather than pretending to know where in it you were.
         */
        data class ChapterOnly(val chapterOrdinal: Int) : Target()

        /** The chapter itself is gone or not downloaded. Nothing to open. */
        object Unavailable : Target()
    }

    /**
     * Resolve a bookmark against the book as it reads now.
     *
     * A bookmark with no frozen snippet (set on an empty or not-yet-downloaded chapter) falls back
     * to its stored offset, which is the best claim available and is honest about being a hint.
     */
    fun resolve(bookmark: Bookmark, book: Book): Target {
        val chapter = book.chapterAt(bookmark.chapterOrdinal) ?: return Target.Unavailable
        if (chapter.text.isBlank()) return Target.Unavailable
        if (bookmark.snippet.isBlank()) {
            return Target.Found(bookmark.chapterOrdinal, bookmark.charOffset.coerceIn(0, chapter.text.length), exact = false)
        }
        val resolution = FuzzyAnchor.resolve(bookmark.anchor(), chapter.text)
        return when (resolution.confidence) {
            FuzzyAnchor.Confidence.EXACT ->
                Target.Found(bookmark.chapterOrdinal, resolution.start, exact = true)
            FuzzyAnchor.Confidence.FUZZY ->
                Target.Found(bookmark.chapterOrdinal, resolution.start, exact = false)
            FuzzyAnchor.Confidence.NONE ->
                Target.ChapterOnly(bookmark.chapterOrdinal)
        }
    }

    /**
     * Whether a bookmark already covers a position, so the reader's bookmark control can toggle
     * rather than pile up near-identical marks on the same page.
     */
    fun existingAt(
        bookmarks: List<Bookmark>,
        chapterOrdinal: Int,
        charOffset: Int,
        tolerance: Int = SAME_PLACE
    ): Bookmark? = bookmarks.firstOrNull {
        it.chapterOrdinal == chapterOrdinal && Math.abs(it.charOffset - charOffset) <= tolerance
    }

    /** Bookmarks in reading order — where they are in the book, not when they were made. */
    fun inReadingOrder(bookmarks: List<Bookmark>): List<Bookmark> =
        bookmarks.sortedWith(compareBy({ it.chapterOrdinal }, { it.charOffset }))

    /**
     * How close counts as "the same place". Roughly a screenful of text: setting a bookmark twice
     * on one page means moving the label, not making a second mark.
     */
    const val SAME_PLACE = 800
}
