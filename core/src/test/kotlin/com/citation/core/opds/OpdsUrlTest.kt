package com.citation.core.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalogs state links every legal way at once, and a person typing a server address types the one
 * they see in a browser rather than the one the protocol wants. Both are absorbed here so neither
 * becomes the user's problem.
 */
class OpdsUrlTest {

    private val base = "https://host.test/opds/dir/page"

    @Test
    fun `every flavour of href resolves`() {
        assertEquals("https://other.test/x", OpdsUrl.resolve(base, "https://other.test/x"))
        assertEquals("https://host.test/root", OpdsUrl.resolve(base, "/root"))
        assertEquals("https://host.test/opds/dir/rel", OpdsUrl.resolve(base, "rel"))
        assertEquals("https://host.test/opds/up", OpdsUrl.resolve(base, "../up"))
        assertEquals("https://host.test/x", OpdsUrl.resolve(base, "//host.test/x"))
    }

    @Test
    fun `an href with a space still resolves instead of losing the link`() {
        assertEquals("https://host.test/opds/dir/a%20b.epub", OpdsUrl.resolve(base, "a b.epub"))
    }

    @Test
    fun `a query is kept intact`() {
        assertEquals(
            "https://host.test/opds/dir/search?q=dune&sort=new",
            OpdsUrl.resolve(base, "search?q=dune&sort=new")
        )
    }

    @Test
    fun `opensearch templates are filled and optional parameters dropped`() {
        val template = OpenSearchDescription.template(OpdsFixtures.openSearch)!!
        assertTrue(template.contains("opds/search"))
        assertEquals(
            "https://example.test/opds/search?q=dune%20messiah",
            OpdsUrl.expandTemplate(template, "dune messiah")
        )
    }

    @Test
    fun `an inline calibre search template expands the same way`() {
        assertEquals(
            "https://host.test/opds/search/the%20expanse",
            OpdsUrl.expandTemplate("https://host.test/opds/search/{searchTerms}", "the expanse")
        )
    }

    @Test
    fun `a typed server address is turned into a catalog root`() {
        assertEquals("http://nas.local:8080/opds", CatalogSource.normalizeRoot("nas.local:8080"))
        assertEquals("http://192.168.1.9:8080/opds", CatalogSource.normalizeRoot("192.168.1.9:8080/"))
        assertEquals("https://standardebooks.org/feeds/opds", CatalogSource.normalizeRoot("https://standardebooks.org/feeds/opds"))
        // A path the user already gave is respected rather than second-guessed.
        assertEquals("http://nas.local:8080/opds/books", CatalogSource.normalizeRoot("http://nas.local:8080/opds/books"))
    }

    @Test
    fun `a bare public hostname is assumed to be https`() {
        assertEquals("https://books.example.org/opds", CatalogSource.normalizeRoot("books.example.org"))
    }

    @Test
    fun `an https catalog never resolves a link down onto plain http`() {
        // What broke browsing Feedbooks: a catalog served over https states its own links with
        // http://, Android refuses cleartext, and the whole page fails on a link nobody typed.
        assertEquals("https://www.feedbooks.com/book/1", OpdsUrl.resolve(base, "http://www.feedbooks.com/book/1"))
        assertEquals("https://host.test/opds/dir/next", OpdsUrl.resolve(base, "http://host.test/opds/dir/next"))
        // Port, query and fragment survive the upgrade untouched.
        assertEquals(
            "https://host.test:8080/opds?page=2#top",
            OpdsUrl.resolve(base, "http://host.test:8080/opds?page=2#top")
        )
    }

    @Test
    fun `a catalog the user added as http stays on http`() {
        val lan = "http://nas.local:8080/opds"
        assertEquals("http://nas.local:8080/books", OpdsUrl.resolve(lan, "books"))
        assertEquals("http://other.local/x", OpdsUrl.resolve(lan, "http://other.local/x"))
        // Only downgrades are refused: a server that upgrades itself is followed gladly.
        assertEquals("https://nas.local/opds", OpdsUrl.resolve(lan, "https://nas.local/opds"))
    }

    @Test
    fun `keepSecure leaves everything that is not an https to http step alone`() {
        assertEquals("data:image/png;base64,AAAA", OpdsUrl.resolve(base, "data:image/png;base64,AAAA"))
        assertEquals("https://host.test/x", OpdsUrl.keepSecure("https://host.test/", "https://host.test/x"))
        assertEquals("http://host.test/x", OpdsUrl.keepSecure("", "http://host.test/x"))
    }

    @Test
    fun `presets are all absolute and distinct`() {
        val presets = CatalogSource.presets()
        assertTrue(presets.isNotEmpty())
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        presets.forEach { assertTrue(it.name, OpdsUrl.isAbsolute(it.rootUrl)) }
        // A public preset that shipped as http:// would be unreachable on Android by policy.
        presets.forEach { assertTrue(it.name, it.rootUrl.startsWith("https://")) }
    }
}
