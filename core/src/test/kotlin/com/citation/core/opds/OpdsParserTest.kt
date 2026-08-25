package com.citation.core.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Browsing a catalog has to work against servers Citation will never see in advance, so the parser
 * is pinned against the shapes the real ones emit: Calibre's `dc:` metadata and series elements,
 * Gutenberg's relative hrefs and navigation-only entries, Standard Ebooks' facets and escaped HTML
 * summaries, a lending catalog's borrow links, and OPDS 2.0 JSON.
 */
class OpdsParserTest {

    private val base = "https://books.example.test/opds/list"

    private fun parse(xml: String, url: String = base) = OpdsParser.parse(xml, url)!!

    @Test
    fun `a calibre acquisition feed yields books with their metadata`() {
        val feed = parse(OpdsFixtures.calibreAcquisition)
        assertEquals("calibre library", feed.title)
        assertEquals(2, feed.publications.size)
        assertTrue(feed.navigation.isEmpty())

        val book = feed.publications.first()
        assertEquals("Leviathan Wakes", book.title)
        assertEquals("James S. A. Corey", book.author)
        assertEquals("Orbit", book.publisher)
        assertEquals("eng", book.language)
        assertEquals(listOf("Science Fiction", "Space Opera"), book.categories)
        assertEquals("Humanity has colonised the solar system.", book.summary)
        assertEquals("The Expanse", book.series)
        assertEquals(1f, book.seriesIndex)
        assertEquals("The Expanse #1", book.seriesLabel)
        assertEquals("9780316129084", book.isbn)
    }

    @Test
    fun `relative hrefs are absolute by the time anyone sees them`() {
        val book = parse(OpdsFixtures.calibreAcquisition).publications.first()
        assertEquals("https://books.example.test/get/EPUB/42/library", book.preferredDownload?.href)
        assertEquals("https://books.example.test/get/thumb/42/library", book.thumbnail)
        assertEquals("https://books.example.test/get/cover/42/library", book.cover)
    }

    @Test
    fun `epub is preferred over pdf when a book offers both`() {
        val book = parse(OpdsFixtures.calibreAcquisition).publications.first()
        assertEquals(OpdsFormat.EPUB, book.preferredDownload?.format)
        assertEquals("EPUB", book.preferredDownload?.formatLabel)
        assertEquals(2, book.downloads.size)
    }

    @Test
    fun `a book offering only pdf still downloads`() {
        val book = parse(OpdsFixtures.calibreAcquisition).publications[1]
        assertEquals(OpdsFormat.PDF, book.preferredDownload?.format)
    }

    @Test
    fun `paging and search links are picked out of the feed`() {
        val feed = parse(OpdsFixtures.calibreAcquisition)
        assertEquals("https://books.example.test/opds/navcatalog/4372?offset=25", feed.next)
        assertNull(feed.previous)
        assertEquals("https://books.example.test/opds", feed.start)
        assertTrue(feed.searchIsTemplate)
    }

    @Test
    fun `a navigation feed yields folders, not books`() {
        val feed = OpdsParser.parse(OpdsFixtures.navigationFeed, "https://m.gutenberg.org/ebooks.opds/")!!
        assertTrue(feed.publications.isEmpty())
        assertEquals(2, feed.navigation.size)
        assertEquals("Popular", feed.navigation.first().title)
        // Relative to the feed's own directory (the base path ends in a slash, so it is one), and
        // classified as navigation from its type alone — this entry states no rel at all.
        assertEquals(
            "https://m.gutenberg.org/ebooks.opds/search.opds/?sort_order=downloads",
            feed.navigation.first().navigationHref
        )
    }

    @Test
    fun `facets are grouped under their headings with the active one marked`() {
        val feed = OpdsParser.parse(OpdsFixtures.facetedFeed, "https://standardebooks.org/feeds/opds/all")!!
        val groups = feed.facets
        assertEquals(listOf("Sort by", "Language"), groups.map { it.name })
        val sort = groups.first()
        assertEquals(2, sort.facets.size)
        assertEquals("Newest", sort.active?.title)
        assertEquals(1200, sort.active?.count)
    }

    @Test
    fun `an escaped html summary is reduced to readable text`() {
        val book = OpdsParser.parse(OpdsFixtures.facetedFeed, "https://standardebooks.org/x")!!
            .publications.single()
        assertEquals("A Victorian scientist travels forward.", book.summary)
    }

    @Test
    fun `open access acquisition counts as a download`() {
        val book = OpdsParser.parse(OpdsFixtures.facetedFeed, "https://standardebooks.org/x")!!
            .publications.single()
        assertEquals(OpdsLinkKind.OPEN_ACCESS, book.preferredDownload?.kind)
        assertTrue(book.preferredDownload!!.kind.isDownload)
    }

    @Test
    fun `a borrowable book is a publication but offers no direct download`() {
        val book = OpdsParser.parse(OpdsFixtures.borrowFeed, "https://library.example.test/opds")!!
            .publications.single()
        assertTrue(book.isPublication)
        assertEquals(1, book.borrowLinks.size)
        assertEquals(1, book.sampleLinks.size)
        assertTrue("a borrow link is not a download", book.downloads.isEmpty())
        assertNull(book.preferredDownload)
    }

    @Test
    fun `an empty feed is a shelf with nothing on it, not a failure`() {
        val feed = OpdsParser.parse(
            "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Empty</title></feed>", base
        )
        assertNotNull(feed)
        assertTrue(feed!!.isEmpty)
        assertEquals("Empty", feed.title)
    }

    @Test
    fun `a single entry response parses as a one book feed`() {
        val xml = """
            <entry xmlns="http://www.w3.org/2005/Atom">
              <title>Just One</title>
              <link rel="http://opds-spec.org/acquisition" href="/one.epub" type="application/epub+zip"/>
            </entry>
        """.trimIndent()
        val feed = OpdsParser.parse(xml, base)!!
        assertEquals("Just One", feed.publications.single().title)
    }

    @Test
    fun `html is rejected rather than parsed into an empty shelf`() {
        val result = CatalogDecoder.decode("<!DOCTYPE html><html><body>Sign in</body></html>", base)
        assertTrue(result is CatalogDecoder.Result.NotACatalog)
        assertTrue((result as CatalogDecoder.Result.NotACatalog).looksLikeHtml)
    }

    @Test
    fun `the decoder recognises both protocols without being told which`() {
        assertTrue(CatalogDecoder.decode(OpdsFixtures.calibreAcquisition, base) is CatalogDecoder.Result.Success)
        assertTrue(CatalogDecoder.decode(OpdsFixtures.opds2, base) is CatalogDecoder.Result.Success)
    }
}
