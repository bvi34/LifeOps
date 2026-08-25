package com.citation.core.epub

import com.citation.core.doc.BlockKind
import com.citation.core.doc.DocumentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of a book that make it look like a book: its real contents, its cover, its
 * illustrations, and the shelf metadata a library screen needs. Everything here is *additive* — the
 * text a chapter reduces to is unchanged, which is what [com.citation.core.doc.HtmlReductionParityTest]
 * guards.
 */
class EpubRichParseTest {

    private fun rich() = EpubParser.parse(EpubFixtures.richEpub())!!

    @Test
    fun `shelf metadata is recovered`() {
        val meta = rich().book.metadata
        assertEquals("Leviathan Wakes", meta.title)
        assertEquals("James S. A. Corey", meta.author)
        assertEquals("Orbit", meta.publisher)
        assertEquals("2011-06-15", meta.published)
        assertEquals("Humanity has colonised the solar system.", meta.description)
        assertEquals(listOf("Science Fiction", "Space Opera"), meta.subjects)
        assertEquals("The Expanse", meta.series)
        assertEquals(1f, meta.seriesIndex)
        assertEquals("The Expanse #1", meta.seriesLabel)
    }

    @Test
    fun `a whole-number series index reads as an integer`() {
        assertEquals("The Expanse #1", rich().book.metadata.seriesLabel)
    }

    @Test
    fun `the epub 3 navigation document gives a nested table of contents`() {
        val toc = rich().book.toc
        assertEquals(1, toc.entries.size)
        val part = toc.entries.single()
        assertEquals("Part One", part.title)
        assertEquals(0, part.chapterOrdinal)
        assertEquals(2, part.children.size)
        assertEquals("Chapter 1: Holden", part.children[0].title)
        assertEquals("start", part.children[0].fragment)
        assertEquals("Chapter 2: Miller", part.children[1].title)
        assertEquals(1, part.children[1].chapterOrdinal)
        // Flattened, the nesting is still legible as depth.
        assertEquals(listOf(0, 1, 1), toc.flatten().map { it.second })
    }

    @Test
    fun `an entry pointing mid-chapter resolves to an offset through the chapter anchors`() {
        val parsed = rich()
        val entry = parsed.book.toc.entries.single().children.first()
        val chapter = parsed.book.chapters[entry.chapterOrdinal!!]
        val offset = chapter.anchors[entry.fragment]
        assertNotNull("fragment should be indexed", offset)
        assertTrue(chapter.text.substring(offset!!).startsWith("The Scopuli had been taken."))
    }

    @Test
    fun `the declared cover is found and its bytes come with the parse`() {
        val parsed = rich()
        assertEquals("OEBPS/img/cover.jpg", parsed.coverPath)
        assertEquals("OEBPS/img/cover.jpg", parsed.book.metadata.coverRef)
        assertTrue(parsed.resources.containsKey("OEBPS/img/cover.jpg"))
    }

    @Test
    fun `illustrations resolve to zip paths from chapter-relative hrefs`() {
        val parsed = rich()
        val image = parsed.book.chapters[0].blocks.filterIsInstance<DocumentBlock.Image>().single()
        // Stated as `../img/plate%20one.png` inside `OEBPS/text/ch1.xhtml`.
        assertEquals("OEBPS/img/plate one.png", image.src)
        assertEquals("The Canterbury", image.alt)
        assertTrue(parsed.resources.containsKey(image.src))
    }

    @Test
    fun `chapter structure survives the trip through the parser`() {
        val chapter = rich().book.chapters[0]
        val kinds = chapter.blocks.filterIsInstance<DocumentBlock.Text>().map { it.kind }
        assertTrue(kinds.contains(BlockKind.HEADING))
        assertTrue(kinds.contains(BlockKind.BLOCKQUOTE))
        assertTrue(chapter.blocks.any { it is DocumentBlock.Image })
    }

    @Test
    fun `an epub 2 ncx gives the same nested contents`() {
        val parsed = EpubParser.parse(EpubFixtures.ncxEpub())!!
        val toc = parsed.book.toc
        assertEquals(1, toc.entries.size)
        assertEquals("Book One", toc.entries.single().title)
        assertEquals(0, toc.entries.single().chapterOrdinal)
        assertEquals("A Section", toc.entries.single().children.single().title)
        assertEquals(1, toc.entries.single().children.single().chapterOrdinal)
    }

    @Test
    fun `an epub 2 cover pointer is followed`() {
        assertEquals("cover.png", EpubParser.parse(EpubFixtures.ncxEpub())!!.coverPath)
    }

    @Test
    fun `a book with no contents document falls back to an empty toc rather than failing`() {
        val parsed = EpubParser.parse(EpubFixtures.twoChapterEpub())!!
        assertTrue(parsed.book.toc.isEmpty)
        assertEquals(2, parsed.book.chapters.size)
        assertNull(parsed.coverPath)
    }

    @Test
    fun `the toc says which entry covers where you are reading`() {
        val toc = rich().book.toc
        assertEquals("Chapter 2: Miller", toc.entryFor(1)?.title)
        // The *deepest* entry covering ordinal 0 is the chapter, not the part that contains it.
        assertEquals("Chapter 1: Holden", toc.entryFor(0)?.title)
    }

    @Test
    fun `a real ao3 export gains its contents and keeps its text`() {
        val bytes = javaClass.getResourceAsStream("/ao3_sample.epub")!!.readBytes()
        val parsed = EpubParser.parse(bytes)!!
        // AO3 ships a toc.ncx; every chapter of the work should be listed.
        assertTrue("expected a contents list, got none", parsed.book.toc.entries.isNotEmpty())
        assertTrue(parsed.book.characterCount > 100_000)
        // And every chapter has structure over that unchanged text.
        assertTrue(parsed.book.chapters.all { it.blocks.isNotEmpty() })
        parsed.book.chapters.forEach { chapter ->
            chapter.blocks.forEach { assertTrue(it.end <= chapter.text.length) }
        }
    }
}
