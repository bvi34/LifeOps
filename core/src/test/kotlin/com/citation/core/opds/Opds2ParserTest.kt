package com.citation.core.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPDS 2.0 is a different serialisation of the same catalog, so the test that matters is that it
 * normalises into the *same* model — a caller must not be able to tell which protocol a shelf came
 * from.
 */
class Opds2ParserTest {

    private val base = "https://comics.example.test/opds/v2/library"

    private fun feed() = Opds2Parser.parse(OpdsFixtures.opds2, base)!!

    @Test
    fun `publications carry the same metadata as the atom form`() {
        val book = feed().publications.single()
        assertEquals("Saga, Volume One", book.title)
        assertEquals("Brian K. Vaughan", book.author)
        assertEquals("Image Comics", book.publisher)
        assertEquals("en", book.language)
        assertEquals("2012-10-23", book.published)
        assertEquals(listOf("Comics", "Science Fiction"), book.categories)
        assertEquals("Saga", book.series)
        assertEquals(1f, book.seriesIndex)
        assertEquals("9781607062011", book.isbn)
    }

    @Test
    fun `images become cover and thumbnail links by size`() {
        val book = feed().publications.single()
        assertEquals("https://comics.example.test/covers/1-large.jpg", book.cover)
        assertEquals("https://comics.example.test/covers/1-thumb.jpg", book.thumbnail)
    }

    @Test
    fun `navigation items become folder entries`() {
        val feed = feed()
        val folder = feed.navigation.single()
        assertEquals("Series", folder.title)
        assertEquals("https://comics.example.test/opds/v2/series", folder.navigationHref)
        assertTrue(folder.navigationHref != null)
    }

    @Test
    fun `paging, search and facets survive the translation`() {
        val feed = feed()
        assertEquals("https://comics.example.test/opds/v2/library?page=2", feed.next)
        assertTrue(feed.searchIsTemplate)
        assertEquals(listOf("Sort by"), feed.facets.map { it.name })
        assertEquals("Title", feed.facets.single().facets.single().title)
    }

    @Test
    fun `an author given as a bare string is read the same as an object`() {
        val json = """
            {"publications":[{"metadata":{"title":"T","author":"A Name"},
             "links":[{"rel":"http://opds-spec.org/acquisition","href":"/t.epub","type":"application/epub+zip"}]}]}
        """.trimIndent()
        assertEquals("A Name", Opds2Parser.parse(json, base)!!.publications.single().author)
    }

    @Test
    fun `several authors are joined`() {
        val json = """
            {"publications":[{"metadata":{"title":"T","author":[{"name":"One"},{"name":"Two"}]},
             "links":[{"rel":"http://opds-spec.org/acquisition","href":"/t.epub","type":"application/epub+zip"}]}]}
        """.trimIndent()
        assertEquals("One, Two", Opds2Parser.parse(json, base)!!.publications.single().author)
    }

    @Test
    fun `unrelated json is not mistaken for a catalog`() {
        assertNull(Opds2Parser.parse("""{"error":"nope"}""", base))
        assertNull(Opds2Parser.parse("not json at all", base))
    }
}
