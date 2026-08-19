package com.citation.core.pdf

import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFlowTest {

    /** A page of prose wrapped at ~62 columns, the way a text extractor emits it. */
    private fun prose(marker: String) = """
        The city had grown used to the sound of the river, and the river
        had grown used to the sound of the city ($marker), so that neither
        of them noticed the other any more.
        Every morning the ferrymen argued about the tide, and every eve-
        ning they agreed that the argument had been worth having.
    """.trimIndent()

    @Test
    fun aScannedPdfHasNoTextLayerAndIsNotReflowed() {
        val scanned = List(40) { "" }
        assertFalse(PdfFlow.hasTextLayer(scanned))
        assertNull(PdfFlow.build(scanned, title = "Scan"))
        // A stray text cover page in front of 40 blank scans is still not a readable book.
        val coverOnly = listOf("Some Title\nA. Author\nPress, 1974") + List(40) { "" }
        assertFalse(PdfFlow.hasTextLayer(coverOnly))
        assertNull(PdfFlow.build(coverOnly, title = "Scan"))
    }

    @Test
    fun aTextPdfReflowsIntoAFlowingBook() {
        val book = PdfFlow.build(List(6) { prose("p$it") }, title = "Ferrymen", author = "A. Author")
        assertNotNull(book)
        book!!
        assertEquals(SourceType.PDF, book.metadata.source)
        assertEquals("Ferrymen", book.metadata.title)
        assertEquals("A. Author", book.metadata.author)
        assertTrue(book.chapters.isNotEmpty())
        assertTrue(book.chapters.all { PdfFlow.isFlowRef(it.sourceRef) })
        // Ordinals are the list positions, as the Book contract requires.
        book.chapters.forEachIndexed { i, c -> assertEquals(i, c.ordinal) }
    }

    @Test
    fun wrappedLinesRejoinAndHyphenatedWordsAreHealed() {
        val text = PdfFlow.build(List(6) { prose("p$it") }, title = "T")!!.chapters.first().text
        // The wrap inside a sentence is gone…
        assertTrue(text.contains("the sound of the river, and the river had grown used to"))
        // …and the line-break hyphen was a word, not punctuation.
        assertTrue(text.contains("every evening they agreed"))
        assertFalse(text.contains("eve-"))
    }

    @Test
    fun paragraphsBreakAtShortLines() {
        val text = PdfFlow.build(List(6) { prose("p$it") }, title = "T")!!.chapters.first().text
        // The short line ending "noticed the other any more." closes a paragraph.
        assertTrue(text.contains("any more.\n\nEvery morning"))
    }

    @Test
    fun runningHeadersFootersAndFoliosAreStripped() {
        val pages = (0 until 8).map { i ->
            "A History of Ferrymen\n\n${prose("p$i")}\n\n${i + 3}"
        }
        val book = PdfFlow.build(pages, title = "T")!!
        val text = book.chapters.joinToString("\n") { it.text }
        assertFalse("running header repeated on every page", text.contains("A History of Ferrymen"))
        // The folio lines are gone too — "3" through "10" as standalone lines.
        assertFalse(text.lines().any { it.trim() == "7" })
    }

    @Test
    fun aHeadingThatOnlyAppearsOnceIsKept() {
        val pages = (0 until 8).map { i ->
            if (i == 4) "Chapter Two: The Tide\n\n${prose("p$i")}" else prose("p$i")
        }
        val text = PdfFlow.build(pages, title = "T")!!.chapters.joinToString("\n") { it.text }
        assertTrue(text.contains("Chapter Two: The Tide"))
    }

    @Test
    fun chaptersChunkPagesWithoutSplittingAPage() {
        // Small target ⇒ one page per chapter, and the titles say which pages they are.
        val book = PdfFlow.build(List(4) { prose("p$it") }, title = "T", targetChars = 10)!!
        assertEquals(4, book.chapters.size)
        assertEquals("Page 1", book.chapters[0].title)
        assertEquals("Page 4", book.chapters[3].title)
        assertEquals(0, PdfFlow.firstPage(book.chapters[0].sourceRef))
        assertEquals(3, PdfFlow.firstPage(book.chapters[3].sourceRef))
    }

    @Test
    fun aMultiPageChapterMapsEveryOffsetBackToItsPage() {
        // One chapter covering all four pages, each page tagged so we can find its text.
        val book = PdfFlow.build(List(4) { prose("p$it") }, title = "T", targetChars = 1_000_000)!!
        assertEquals(1, book.chapters.size)
        val chapter = book.chapters.single()
        assertEquals("Pages 1–4", chapter.title)

        for (page in 0 until 4) {
            val offset = chapter.text.indexOf("p$page")
            assertTrue("page marker p$page present", offset >= 0)
            assertEquals(page, PdfFlow.pageOf(chapter, offset))
        }
        // Offsets outside the mapped range clamp to the first / last page rather than failing.
        assertEquals(0, PdfFlow.pageOf(chapter, -5))
        assertEquals(3, PdfFlow.pageOf(chapter, chapter.text.length + 100))
    }

    @Test
    fun aBlankPageInsideAChapterDoesNotShiftLaterPages() {
        val pages = listOf(prose("p0"), "", prose("p2"), prose("p3"))
        val chapter = PdfFlow.build(pages, title = "T", targetChars = 1_000_000)!!.chapters.single()
        assertEquals(2, PdfFlow.pageOf(chapter, chapter.text.indexOf("p2")))
        assertEquals(3, PdfFlow.pageOf(chapter, chapter.text.indexOf("p3")))
    }

    @Test
    fun aPageMapsBackToTheChapterThatCoversIt() {
        val book = PdfFlow.build(List(9) { prose("p$it") }, title = "T", targetChars = 10)!!
        assertEquals(9, book.chapters.size)
        assertEquals(0, PdfFlow.chapterForPage(book.chapters, 0))
        assertEquals(5, PdfFlow.chapterForPage(book.chapters, 5))
        // Out-of-range pages clamp rather than throwing — track switching must never fail.
        assertEquals(0, PdfFlow.chapterForPage(book.chapters, -1))
        assertEquals(8, PdfFlow.chapterForPage(book.chapters, 400))
        assertEquals(0, PdfFlow.chapterForPage(emptyList(), 3))
    }

    @Test
    fun theRefRoundTripsAndForeignRefsAreRejected() {
        val ref = PdfFlow.encodeRef(firstPage = 12, breaks = listOf(0, 1834, 3502))
        assertEquals(12, PdfFlow.firstPage(ref))
        assertEquals(12, PdfFlow.pageOf(ref, 0))
        assertEquals(13, PdfFlow.pageOf(ref, 1834))
        assertEquals(13, PdfFlow.pageOf(ref, 3501))
        assertEquals(14, PdfFlow.pageOf(ref, 9999))

        assertFalse(PdfFlow.isFlowRef("OEBPS/chapter3.xhtml"))
        assertNull(PdfFlow.firstPage("OEBPS/chapter3.xhtml"))
        assertNull(PdfFlow.pageOf("OEBPS/chapter3.xhtml", 40))
    }
}
