package com.citation.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AO3 is ingested via its **official EPUB download** (a Calibre-produced EPUB 2.0 with a root-level
 * `content.opf`, split `*_split_NNN.xhtml` chapters, and a `toc.ncx`). This pins that a real AO3
 * export parses through the shared [EpubParser] — the whole reason AO3 ingestion is the robust EPUB
 * path rather than fragile HTML scraping. The fixture is a real AO3 download in test resources.
 */
class Ao3EpubTest {

    private fun sample(): ByteArray =
        javaClass.getResourceAsStream("/ao3_sample.epub")
            ?.readBytes() ?: error("ao3_sample.epub missing from test resources")

    @Test
    fun parsesRealAo3CalibreExport() {
        val parsed = EpubParser.parse(sample())
        assertNotNull("AO3 EPUB should parse, not return null", parsed)
        parsed!!
        assertEquals("Shepard: A Survivor's Saga", parsed.book.metadata.title)
        assertEquals("twistedwit", parsed.book.metadata.author)
        // A multi-chapter work: the spine resolves to many ordered chapters of real text.
        assertTrue("expected many chapters, got ${parsed.book.chapters.size}", parsed.book.chapters.size > 10)
        assertTrue(parsed.book.characterCount > 100_000)
        assertEquals(parsed.book.chapters.indices.toList(), parsed.book.chapters.map { it.ordinal })
    }
}
