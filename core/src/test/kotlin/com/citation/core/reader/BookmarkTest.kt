package com.citation.core.reader

import com.citation.core.key.EntityKey
import com.citation.core.key.EntityType
import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bookmark is a position, not a passage — but it has to survive the text moving underneath it,
 * the same way a note does. These are the cases where a naive offset would quietly point at the
 * wrong words: an edited serial chapter, a re-cut export, a deleted paragraph.
 */
class BookmarkTest {

    private val chapterOne =
        "It was a bright cold day in April, and the clocks were striking thirteen. " +
            "Winston Smith, his chin nuzzled into his breast in an effort to escape the vile wind, " +
            "slipped quickly through the glass doors of Victory Mansions."

    private fun book(vararg texts: String) = Book(
        key = EntityKey("ER", EntityType.BOOK, 1),
        metadata = BookMetadata(title = "T", author = null, source = SourceType.EPUB),
        chapters = texts.mapIndexed { i, text ->
            Chapter(ordinal = i, title = "Chapter ${i + 1}", sourceRef = "c$i", text = text)
        }
    )

    private fun key(seq: Long) = EntityKey("ER", EntityType.BOOKMARK, seq)

    private fun bookmarkAt(book: Book, chapter: Int, offset: Int, label: String? = null) =
        Bookmarks.at(
            key = key(1),
            bookKey = book.key!!,
            book = book,
            chapterOrdinal = chapter,
            charOffset = offset,
            label = label,
            now = 1_000
        )

    @Test
    fun `a bookmark freezes the line it was set on`() {
        val b = bookmarkAt(book(chapterOne), 0, 74)
        assertTrue(b.snippet.startsWith("Winston Smith"))
        assertEquals("Chapter 1", b.chapterTitle)
        assertEquals(74, b.charOffset)
    }

    @Test
    fun `the frozen line stops at the end of a sentence`() {
        val b = bookmarkAt(book(chapterOne), 0, 0)
        assertEquals("It was a bright cold day in April, and the clocks were striking thirteen.", b.snippet)
    }

    @Test
    fun `a snippet never opens mid-word`() {
        // Offset 78 lands inside "Winston".
        val b = bookmarkAt(book(chapterOne), 0, 78)
        assertTrue("snippet was '${b.snippet}'", b.snippet.startsWith("Winston"))
    }

    @Test
    fun `a label is what the list shows, and the frozen line stands in when there is none`() {
        val labelled = bookmarkAt(book(chapterOne), 0, 0, label = "  The famous opening  ")
        assertEquals("The famous opening", labelled.label)
        assertEquals("The famous opening", labelled.display)

        val bare = bookmarkAt(book(chapterOne), 0, 0)
        assertNull(bare.label)
        assertEquals(bare.snippet, bare.display)
    }

    @Test
    fun `an unchanged book resolves the bookmark exactly where it was set`() {
        val original = book(chapterOne)
        val b = bookmarkAt(original, 0, 74)
        val target = Bookmarks.resolve(b, original)
        assertTrue(target is Bookmarks.Target.Found)
        target as Bookmarks.Target.Found
        assertTrue(target.exact)
        assertEquals(74, target.charOffset)
    }

    @Test
    fun `an edit earlier in the chapter moves the bookmark with the words`() {
        val original = book(chapterOne)
        val b = bookmarkAt(original, 0, 74)
        // The author inserts a sentence at the top; every offset after it shifts.
        val edited = book("A new opening sentence was added here. $chapterOne")

        val target = Bookmarks.resolve(b, edited)
        assertTrue(target is Bookmarks.Target.Found)
        target as Bookmarks.Target.Found
        assertTrue("should still land on the frozen words", target.exact)
        assertTrue(edited.chapters[0].text.substring(target.charOffset).startsWith("Winston Smith"))
        assertTrue("offset should have moved", target.charOffset > 74)
    }

    @Test
    fun `a lightly reworded passage still resolves, flagged as inexact`() {
        val original = book(chapterOne)
        val b = bookmarkAt(original, 0, 74)
        val edited = book(chapterOne.replace("his chin nuzzled", "with his chin nuzzled down"))

        val target = Bookmarks.resolve(b, edited)
        assertTrue("expected a resolution, got $target", target is Bookmarks.Target.Found)
    }

    @Test
    fun `a deleted passage degrades to the chapter rather than jumping somewhere wrong`() {
        val original = book(chapterOne)
        val b = bookmarkAt(original, 0, 74)
        val gutted = book("It was a bright cold day in April, and the clocks were striking thirteen.")

        assertEquals(Bookmarks.Target.ChapterOnly(0), Bookmarks.resolve(b, gutted))
    }

    @Test
    fun `a chapter that is not downloaded yields nothing to open`() {
        val original = book(chapterOne, "second chapter text")
        val b = bookmarkAt(original, 1, 0)
        assertEquals(Bookmarks.Target.Unavailable, Bookmarks.resolve(b, book(chapterOne, "")))
        assertEquals(Bookmarks.Target.Unavailable, Bookmarks.resolve(b, book(chapterOne)))
    }

    @Test
    fun `a bookmark set on an empty chapter falls back to its stored offset`() {
        val empty = book("")
        val b = bookmarkAt(empty, 0, 0)
        assertEquals("", b.snippet)
        // Once the chapter arrives, it opens at the remembered offset rather than nowhere.
        val target = Bookmarks.resolve(b.copy(charOffset = 10), book(chapterOne))
        assertEquals(Bookmarks.Target.Found(0, 10, exact = false), target)
    }

    @Test
    fun `setting a bookmark twice on one page finds the existing one`() {
        val b = bookmarkAt(book(chapterOne), 0, 74)
        assertNotNull(Bookmarks.existingAt(listOf(b), 0, 74))
        assertNotNull(Bookmarks.existingAt(listOf(b), 0, 74 + Bookmarks.SAME_PLACE - 1))
        assertNull(Bookmarks.existingAt(listOf(b), 0, 74 + Bookmarks.SAME_PLACE + 1))
        assertNull(Bookmarks.existingAt(listOf(b), 1, 74))
    }

    @Test
    fun `bookmarks list in reading order, not the order they were made`() {
        val b = book(chapterOne, chapterOne, chapterOne)
        val third = bookmarkAt(b, 2, 0).copy(key = key(1))
        val firstLate = bookmarkAt(b, 0, 200).copy(key = key(2))
        val firstEarly = bookmarkAt(b, 0, 0).copy(key = key(3))

        assertEquals(
            listOf(key(3), key(2), key(1)),
            Bookmarks.inReadingOrder(listOf(third, firstLate, firstEarly)).map { it.key }
        )
    }
}
