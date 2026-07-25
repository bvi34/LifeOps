package com.citation.core.epub

import com.citation.core.identity.IdentityKey
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubParserTest {

    @Test
    fun parsesMetadataChaptersAndIsbn() {
        val parsed = EpubParser.parse(EpubFixtures.twoChapterEpub())
        assertNotNull(parsed)
        parsed!!

        assertEquals("The Test Book", parsed.book.metadata.title)
        assertEquals("Ada Lovelace", parsed.book.metadata.author)
        assertEquals("en", parsed.book.metadata.language)
        assertEquals(SourceType.EPUB, parsed.book.metadata.source)

        assertEquals(2, parsed.book.chapters.size)
        assertEquals(0, parsed.book.chapters[0].ordinal)
        assertEquals(1, parsed.book.chapters[1].ordinal)
        assertEquals("Chapter One", parsed.book.chapters[0].title)
        assertTrue(parsed.book.chapters[0].text.contains("striking thirteen"))
        // HTML tags must be gone from the flowing text.
        assertTrue(!parsed.book.chapters[0].text.contains("<"))

        val isbn = parsed.identity.strongest as? IdentityKey.Isbn
        assertNotNull(isbn)
        assertEquals("9780132350884", isbn!!.normalized)
    }

    @Test
    fun spineDeterminesReadingOrder() {
        val parsed = EpubParser.parse(EpubFixtures.twoChapterEpub())!!
        assertEquals("ch1.xhtml", parsed.book.chapters[0].sourceRef.substringAfterLast('/'))
        assertEquals("ch2.xhtml", parsed.book.chapters[1].sourceRef.substringAfterLast('/'))
    }

    @Test
    fun garbageBytesDegradeToNull() {
        assertNull(EpubParser.parse(byteArrayOf(1, 2, 3, 4, 5)))
    }
}
