package com.citation.core.reader

import com.citation.core.doc.BlockKind
import com.citation.core.doc.DocumentBlock
import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following a note reference or a cross-reference the text itself states.
 *
 * The cases that matter are the ones where the reference cannot be honoured: a fragment this book
 * does not contain, a file outside the spine, an address that belongs to somebody else. A reader
 * that guesses at those lands you in the wrong note, which is worse than not moving.
 */
class BookReferencesTest {

    private val notesText = "1. Auden said so, in 1939.\n\n2. And Orwell disagreed, at length."

    private val book = Book(
        key = null,
        metadata = BookMetadata(title = "T", author = null, source = SourceType.EPUB),
        chapters = listOf(
            Chapter(
                ordinal = 0,
                title = "One",
                sourceRef = "OEBPS/text/ch1.xhtml",
                text = "A claim1 and a second claim2.",
                anchors = mapOf("top" to 0, "mid" to 10)
            ),
            Chapter(
                ordinal = 1,
                title = "Notes",
                sourceRef = "OEBPS/text/notes.xhtml",
                text = notesText,
                blocks = listOf(
                    DocumentBlock.Text(start = 0, end = 26, kind = BlockKind.LIST_ITEM),
                    DocumentBlock.Text(start = 28, end = notesText.length, kind = BlockKind.LIST_ITEM)
                ),
                anchors = mapOf("fn1" to 0, "fn2" to 28)
            )
        )
    )

    // --- Where a reference points ---------------------------------------------------------------

    @Test
    fun `a note reference lands on the note, not on the file holding it`() {
        val target = BookReferences.resolve("OEBPS/text/notes.xhtml#fn2", book, fromChapter = 0)
        assertEquals(ReferenceTarget.InBook(1, 28), target)
    }

    @Test
    fun `a bare fragment is relative to the chapter that stated it`() {
        assertEquals(
            ReferenceTarget.InBook(0, 10),
            BookReferences.resolve("#mid", book, fromChapter = 0)
        )
    }

    @Test
    fun `a file with no fragment opens that chapter at its beginning`() {
        assertEquals(
            ReferenceTarget.InBook(1, 0),
            BookReferences.resolve("OEBPS/text/notes.xhtml", book, fromChapter = 0)
        )
    }

    @Test
    fun `a fragment this book does not contain resolves to nothing at all`() {
        // The whole point. Landing at the top of the notes file and showing note 1 in answer to a
        // tap on footnote 17 is not a degraded answer, it is a confident wrong one.
        assertNull(BookReferences.resolve("OEBPS/text/notes.xhtml#fn9", book, fromChapter = 0))
        assertNull(BookReferences.resolve("#nowhere", book, fromChapter = 0))
    }

    @Test
    fun `a file outside the spine is a link the reader cannot follow`() {
        assertNull(BookReferences.resolve("OEBPS/text/colophon.xhtml", book, fromChapter = 0))
    }

    @Test
    fun `anything stating a scheme belongs to somebody else`() {
        assertEquals(
            ReferenceTarget.External("https://example.com/notes#fn1"),
            BookReferences.resolve("https://example.com/notes#fn1", book, fromChapter = 0)
        )
        assertTrue(BookReferences.resolve("mailto:a@b.c", book, 0) is ReferenceTarget.External)
        assertTrue(BookReferences.resolve("HTTPS://EXAMPLE.COM", book, 0) is ReferenceTarget.External)
    }

    @Test
    fun `an empty or missing href is not a reference`() {
        assertNull(BookReferences.resolve(null, book, fromChapter = 0))
        assertNull(BookReferences.resolve("   ", book, fromChapter = 0))
    }

    @Test
    fun `a reference from a chapter that is not there resolves to nothing`() {
        assertNull(BookReferences.resolve("#mid", book, fromChapter = 9))
    }

    // --- What the note says ---------------------------------------------------------------------

    @Test
    fun `the note shown is the one pointed at, and stops where it ends`() {
        val second = ReferenceTarget.InBook(1, 28)
        assertEquals("2. And Orwell disagreed, at length.", BookReferences.note(book, second))
        val first = ReferenceTarget.InBook(1, 0)
        assertEquals("1. Auden said so, in 1939.", BookReferences.note(book, first))
    }

    @Test
    fun `a book with no structure recovered falls back to the paragraph`() {
        val flat = book.copy(chapters = listOf(book.chapters[0], book.chapters[1].copy(blocks = emptyList())))
        assertEquals("2. And Orwell disagreed, at length.", BookReferences.note(flat, ReferenceTarget.InBook(1, 28)))
        assertEquals("1. Auden said so, in 1939.", BookReferences.note(flat, ReferenceTarget.InBook(1, 0)))
    }

    @Test
    fun `a note nested inside a larger block is read from the innermost`() {
        val nested = book.chapters[1].copy(
            blocks = book.chapters[1].blocks + DocumentBlock.Text(start = 0, end = notesText.length, kind = BlockKind.PARAGRAPH)
        )
        val whole = book.copy(chapters = listOf(book.chapters[0], nested))
        assertEquals("1. Auden said so, in 1939.", BookReferences.note(whole, ReferenceTarget.InBook(1, 0)))
    }

    @Test
    fun `an endnote that runs to a page is cut on a word, and says so`() {
        val long = "A note. " + "word ".repeat(400)
        val bulky = book.copy(
            chapters = listOf(
                book.chapters[0],
                Chapter(ordinal = 1, title = "Notes", sourceRef = "n", text = long, anchors = mapOf("fn1" to 0))
            )
        )
        val target = ReferenceTarget.InBook(1, 0)
        val shown = BookReferences.note(bulky, target)!!
        assertTrue(shown.length <= BookReferences.NOTE_LIMIT + 1)
        assertTrue("a cut has to be visible", shown.endsWith("…"))
        assertTrue("and never mid-word", shown.dropLast(1).trimEnd().endsWith("word"))
        assertTrue("so the reader can be offered the rest", BookReferences.noteIsCut(bulky, target))
    }

    @Test
    fun `a short note is not cut and does not claim to be`() {
        val target = ReferenceTarget.InBook(1, 0)
        assertTrue(!BookReferences.noteIsCut(book, target))
        assertTrue(!BookReferences.note(book, target)!!.endsWith("…"))
    }

    @Test
    fun `an anchor pointing at nothing readable yields no note rather than an empty popup`() {
        val blank = book.copy(
            chapters = listOf(
                book.chapters[0],
                Chapter(ordinal = 1, title = "Notes", sourceRef = "n", text = "   ", anchors = mapOf("fn1" to 0))
            )
        )
        assertNull(BookReferences.note(blank, ReferenceTarget.InBook(1, 0)))
        assertNull(BookReferences.note(book, ReferenceTarget.InBook(9, 0)))
    }
}
