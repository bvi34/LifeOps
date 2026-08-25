package com.citation.core.reader

import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Searching the book you are reading. The cases that matter are the ones where the text you
 * remember is not quite the text on the page: different case, a phrase the source happens to break
 * across a line, whitespace that does not match what you typed.
 */
class BookSearchTest {

    private fun book(vararg chapters: Pair<String, String>) = Book(
        key = null,
        metadata = BookMetadata(title = "T", author = null, source = SourceType.EPUB),
        chapters = chapters.mapIndexed { i, (title, text) ->
            Chapter(ordinal = i, title = title, sourceRef = "c$i", text = text)
        }
    )

    private val sample = book(
        "Chapter One" to "It was a bright cold day in April, and the clocks were striking thirteen.",
        "Chapter Two" to "The sky above the port was the color of television, tuned to a dead channel.",
        "Chapter Three" to "The clocks\nwere striking again, and the clocks kept striking."
    )

    @Test
    fun `finds a phrase and says where it is`() {
        val hits = BookSearch.search(sample, "clocks were striking")
        assertEquals(2, hits.size)
        assertEquals(0, hits[0].chapterOrdinal)
        assertEquals("Chapter One", hits[0].chapterTitle)
        assertEquals("clocks were striking", sample.chapters[0].text.substring(hits[0].range))
    }

    @Test
    fun `matching folds case`() {
        assertEquals(1, BookSearch.search(sample, "BRIGHT COLD").size)
        assertEquals(1, BookSearch.search(sample, "bright cold").size)
    }

    @Test
    fun `a phrase broken across a line still matches`() {
        // Chapter three has "The clocks\nwere striking" — a reader typing it as one line means it.
        val hits = BookSearch.search(sample, "clocks were striking")
        val third = hits.single { it.chapterOrdinal == 2 }
        assertEquals("clocks\nwere striking", sample.chapters[2].text.substring(third.range))
    }

    @Test
    fun `extra spaces in the query do not matter`() {
        assertEquals(
            BookSearch.search(sample, "bright cold").size,
            BookSearch.search(sample, "  bright    cold  ").size
        )
    }

    @Test
    fun `results come back in reading order`() {
        val hits = BookSearch.search(sample, "the")
        assertEquals(hits.map { it.chapterOrdinal }, hits.map { it.chapterOrdinal }.sorted())
        hits.groupBy { it.chapterOrdinal }.forEach { (_, inChapter) ->
            assertEquals(inChapter.map { it.offset }, inChapter.map { it.offset }.sorted())
        }
    }

    @Test
    fun `every hit reports a range that really holds the query`() {
        BookSearch.search(sample, "striking").forEach { hit ->
            val text = sample.chapters[hit.chapterOrdinal].text
            assertEquals("striking", text.substring(hit.range).lowercase())
        }
    }

    @Test
    fun `repeated occurrences in one chapter are all found`() {
        val hits = BookSearch.search(sample, "clocks").filter { it.chapterOrdinal == 2 }
        assertEquals(2, hits.size)
        assertTrue(hits[0].offset < hits[1].offset)
    }

    @Test
    fun `a one letter query is refused rather than matching everything`() {
        assertTrue(BookSearch.search(sample, "a").isEmpty())
        assertTrue(BookSearch.search(sample, " ").isEmpty())
        assertTrue(BookSearch.search(sample, "").isEmpty())
    }

    @Test
    fun `a chapter with no downloaded text contributes nothing rather than a false negative`() {
        val serial = book("One" to "the clocks", "Two (not fetched)" to "", "Three" to "the clocks")
        val hits = BookSearch.search(serial, "clocks")
        assertEquals(listOf(0, 2), hits.map { it.chapterOrdinal })
    }

    @Test
    fun `the result count is capped`() {
        val long = book("One" to "ab ".repeat(500))
        assertEquals(10, BookSearch.search(long, "ab", limit = 10).size)
    }

    @Test
    fun `chapters with hits is what a result header needs`() {
        assertEquals(3, BookSearch.chaptersWithHits(BookSearch.search(sample, "the")))
    }

    // --- Snippets --------------------------------------------------------------------------------

    @Test
    fun `a snippet shows the match in context and says where it sits`() {
        val text = sample.chapters[0].text
        val range = BookSearch.findIn(text, "clocks").single()
        val snippet = BookSearch.snippetOf(text, range)
        assertEquals("clocks", snippet.text.substring(snippet.match))
        assertTrue(snippet.text.contains("the clocks were striking"))
    }

    @Test
    fun `a clipped snippet says so at the clipped end only`() {
        val text = "x".repeat(200) + " needle " + "y".repeat(200)
        val range = BookSearch.findIn(text, "needle").single()
        val snippet = BookSearch.snippetOf(text, range)
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        assertEquals("needle", snippet.text.substring(snippet.match))
    }

    @Test
    fun `a match at the very start is not given a leading ellipsis`() {
        val text = "Needle at the front of a fairly long line of text that keeps going onward."
        val range = BookSearch.findIn(text, "needle").single()
        val snippet = BookSearch.snippetOf(text, range)
        assertTrue(snippet.text.startsWith("Needle"))
        assertEquals("Needle", snippet.text.substring(snippet.match))
    }

    @Test
    fun `a snippet is one line even when the match spans a paragraph break`() {
        val text = "First paragraph ends here.\n\nAnd the needle is in the second."
        val range = BookSearch.findIn(text, "needle").single()
        val snippet = BookSearch.snippetOf(text, range)
        assertTrue(!snippet.text.contains('\n'))
        assertEquals("needle", snippet.text.substring(snippet.match))
    }

    @Test
    fun `every snippet locates its own match, over a whole book`() {
        listOf("the", "clocks", "striking", "a dead channel", "It was").forEach { query ->
            BookSearch.search(sample, query).forEach { hit ->
                assertEquals(
                    "snippet match wrong for '$query' in ${hit.chapterTitle}",
                    hit.snippet.substring(hit.snippetMatch).lowercase().replace('\n', ' '),
                    BookSearch.normalize(hit.snippet.substring(hit.snippetMatch))
                )
            }
        }
    }
}
