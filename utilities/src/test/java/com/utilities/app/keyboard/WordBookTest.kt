package com.utilities.app.keyboard

import com.utilities.app.keyboard.logic.WordBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The general dictionary, read and searched.
 *
 * The thing worth pinning here is the binary search, because its failure mode is silence: a word
 * book that is looked up wrongly does not throw, it simply reports that half of English is not
 * English, and the only symptom is a keyboard that quietly stops correcting. So the words either
 * side of every boundary — the first, the last, one that is a prefix of another, one that is not
 * there at all — are all asked for by name.
 */
class WordBookTest {

    private val book = WordBook.parse(
        """
        # a comment, and the licence notice the real file carries
        1and
        3andante
        2ante
        1the
        3theatre
        1them
        1they
        """.trimIndent()
    )

    @Test
    fun `every word is found, including the first and the last`() {
        listOf("and", "andante", "ante", "the", "theatre", "them", "they").forEach { word ->
            assertTrue("$word should be in the book", book.contains(word))
        }
        assertEquals(7, book.size)
    }

    @Test
    fun `a word that is not there is not found, however close it sits to one that is`() {
        listOf("an", "ands", "anda", "th", "thex", "thez", "aardvark", "zebra", "").forEach { word ->
            assertFalse("$word should not be in the book", book.contains(word))
        }
    }

    @Test
    fun `how common a word is comes back with it`() {
        assertEquals(1, book.tier("the"))
        assertEquals(2, book.tier("ante"))
        assertEquals(3, book.tier("theatre"))
        assertEquals(0, book.tier("nonsense"))
    }

    @Test
    fun `completions come back commonest first, then shortest`() {
        assertEquals(listOf("them", "they", "theatre"), book.suggest("the", 3))
        assertEquals(listOf("them"), book.suggest("the", 1))
    }

    @Test
    fun `the prefix itself is never offered back`() {
        assertFalse("the" in book.suggest("the", 3))
        assertEquals(emptyList<String>(), book.suggest("they", 3))
    }

    @Test
    fun `a prefix nothing starts with suggests nothing`() {
        assertEquals(emptyList<String>(), book.suggest("zzz", 3))
        assertEquals(emptyList<String>(), book.suggest("", 3))
    }

    @Test
    fun `comments and malformed lines are skipped rather than fatal`() {
        val parsed = WordBook.parse("# comment\n\n1good\n9bad\nnodigit\n4alsobad\n2fine")
        assertEquals(2, parsed.size)
        assertTrue(parsed.contains("good"))
        assertTrue(parsed.contains("fine"))
        assertFalse(parsed.contains("bad"))
        assertFalse(parsed.contains("odigit"))
    }

    @Test
    fun `a file in the wrong order is put right rather than half-searched`() {
        val jumbled = WordBook.parse("1the\n1and\n2ante")
        assertTrue(jumbled.contains("and"))
        assertTrue(jumbled.contains("the"))
        assertEquals(2, jumbled.tier("ante"))
        assertEquals(listOf("and", "ante"), jumbled.suggest("an", 3))
    }

    @Test
    fun `a keyboard whose dictionary has not loaded yet knows nothing and does not mind`() {
        assertEquals(0, WordBook.EMPTY.size)
        assertFalse(WordBook.EMPTY.contains("the"))
        assertEquals(0, WordBook.EMPTY.tier("the"))
        assertEquals(emptyList<String>(), WordBook.EMPTY.suggest("the", 3))
    }
}
