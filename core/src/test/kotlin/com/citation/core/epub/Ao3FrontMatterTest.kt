package com.citation.core.epub

import com.citation.core.model.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An AO3 download's first spine document is its tag record, not its story — so parsed literally, a
 * fic opens on "Rating: … Archive Warning: … Fandoms: …" run together in one block. [Ao3Export]
 * reshapes that: the title page comes first, the record moves to the back as *Work Details*, and
 * the contents document is remapped to match. This pins the whole reshaping, on a synthetic export
 * and on a real AO3 download in test resources.
 */
class Ao3FrontMatterTest {

    private fun fic(): Book = EpubParser.parse(EpubFixtures.ao3Epub())!!.book

    private fun realExport(): Book =
        EpubParser.parse(
            javaClass.getResourceAsStream("/ao3_sample.epub")?.readBytes()
                ?: error("ao3_sample.epub missing from test resources")
        )!!.book

    @Test
    fun `a fic opens on its title page, not on its tags`() {
        val book = fic()
        val first = book.chapters.first()
        assertEquals("A Test Fic — anon_writes", first.title)
        assertTrue(first.text.startsWith("A Test Fic"))
        assertTrue(first.text.contains("by anon_writes"))
        assertTrue(first.text.contains("They meet. It goes badly."))
        assertFalse("the tag record must not lead the book", first.text.contains("Rating:"))
    }

    @Test
    fun `the tag record becomes a work details section at the end`() {
        val book = fic()
        assertEquals(
            listOf("A Test Fic — anon_writes", "Chapter 1", "Afterword", "Work Details"),
            book.chapters.map { it.title }
        )
        assertEquals(book.chapters.indices.toList(), book.chapters.map { it.ordinal })
    }

    @Test
    fun `each tag group is its own line rather than one run-on wall`() {
        val details = fic().chapters.last()
        val lines = details.text.lines().filter { it.isNotBlank() }
        assertEquals("Work Details", lines.first())
        assertTrue(lines.contains("Rating: General Audiences"))
        assertTrue(lines.contains("Fandoms: Fandom A, Fandom B"))
        // Entities survive the rewrite: an ampersand in a tag is still an ampersand.
        assertTrue(lines.contains("Characters: Bee & Cee"))
        // AO3 writes each statistic on its own line; they read as one labelled line.
        assertTrue(lines.contains("Stats: Published: 2026-08-24 · Words: 4,241 · Chapters: 1/1"))
        // The work stays traceable to where it came from.
        assertTrue(lines.contains("Source: https://archiveofourown.org/works/12345"))
    }

    @Test
    fun `the contents lists the title page AO3 omits and the details last`() {
        val toc = fic().toc
        assertEquals(
            listOf("A Test Fic — anon_writes", "Chapter 1", "Afterword", "Work Details"),
            toc.entries.map { it.title }
        )
        assertEquals(listOf(0, 1, 2, 3), toc.entries.map { it.chapterOrdinal })
    }

    @Test
    fun `contents entries still land on the chapter they name`() {
        val book = fic()
        book.toc.entries.forEach { entry ->
            val ordinal = entry.chapterOrdinal
            assertNotNull("entry '${entry.title}' lost its target", ordinal)
            assertEquals(entry.title, book.chapters[ordinal!!].title)
        }
    }

    @Test
    fun `a real AO3 download is reshaped the same way`() {
        val book = realExport()
        assertEquals("Shepard: A Survivor's Saga — twistedwit", book.chapters.first().title)
        assertTrue(book.chapters.first().text.contains("by twistedwit"))
        assertEquals("Work Details", book.chapters.last().title)
        assertTrue(book.chapters.last().text.contains("Rating:"))
        assertTrue(book.chapters.last().text.contains("Fandoms:"))
        assertEquals(book.chapters.indices.toList(), book.chapters.map { it.ordinal })
        // Nothing was lost: every spine document is still a chapter.
        assertEquals(42, book.chapters.size)
        assertEquals("Work Details", book.toc.entries.last().title)
        assertEquals(book.chapters.size - 1, book.toc.entries.last().chapterOrdinal)
    }

    @Test
    fun `the head's fandom line is not reprinted above every chapter`() {
        val book = realExport()
        // AO3 puts "fic - author - every fandom" in each document's <head>; the reduction has no
        // notion of a head, so untreated it lands above the first words of every single chapter.
        assertTrue(
            book.chapters.none { it.text.contains("twistedwit - Mass Effect Trilogy") }
        )
        assertTrue(book.chapters[1].text.startsWith("Chapter 1: The Sentinel"))
    }

    @Test
    fun `stripping the head is AO3-only, so no other source's text shifts`() {
        // The reduction is the surface notes anchor against, so it is changed for AO3 documents
        // alone: an ordinary EPUB still reduces to exactly the characters it always did, head
        // title included.
        val plain = EpubParser.parse(EpubFixtures.twoChapterEpub())!!.book
        assertTrue(plain.chapters.first().text.startsWith("t\n"))
    }

    @Test
    fun `an ordinary EPUB is left exactly as its spine stated it`() {
        val plain = EpubParser.parse(EpubFixtures.richEpub())!!.book
        assertTrue(plain.chapters.none { it.title == "Work Details" })
        assertEquals("Part One", plain.toc.entries.single().title)
        assertEquals(2, plain.toc.entries.single().children.size)
    }
}
