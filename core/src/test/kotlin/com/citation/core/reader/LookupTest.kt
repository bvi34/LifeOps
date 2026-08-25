package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hard part of "look this word up" is deciding what the word is. A selection made in a book
 * arrives wrapped in the punctuation a book is full of, and handing that to a dictionary returns
 * nothing — which reads as the feature being broken rather than the query being wrong.
 */
class LookupTest {

    @Test
    fun `a plain word is a word`() {
        val q = Lookup.of("mansions")
        assertEquals("mansions", q.term)
        assertEquals(Lookup.Kind.WORD, q.kind)
    }

    @Test
    fun `book punctuation is stripped from the ends`() {
        assertEquals("mansions", Lookup.of("mansions.").term)
        assertEquals("Whither", Lookup.of("“Whither,").term)
        assertEquals("vile", Lookup.of("  vile;  ").term)
        assertEquals("thirteen", Lookup.of("thirteen—").term)
        assertEquals("word", Lookup.of("(word)").term)
    }

    @Test
    fun `punctuation inside a word is part of the word`() {
        assertEquals("don't", Lookup.of("don't").term)
        assertEquals("well-being", Lookup.of("well-being,").term)
        assertEquals("O’Brien", Lookup.of("“O’Brien”").term)
    }

    @Test
    fun `a phrase is not offered to a dictionary`() {
        val q = Lookup.of("the clocks were striking thirteen")
        assertEquals(Lookup.Kind.PHRASE, q.kind)
        assertTrue(Lookup.targetsFor(q).none { it == Lookup.Target.DICTIONARY })
        assertEquals(Lookup.Target.SEARCH, Lookup.targetsFor(q).first())
    }

    @Test
    fun `a word gets a dictionary first`() {
        assertEquals(Lookup.Target.DICTIONARY, Lookup.targetsFor(Lookup.of("vile")).first())
    }

    @Test
    fun `an accidental paragraph selection is cut to something searchable`() {
        val paragraph = "It was a bright cold day in April, and the clocks were striking thirteen. " +
            "Winston Smith, his chin nuzzled into his breast in an effort to escape the vile wind, " +
            "slipped quickly through the glass doors."
        val q = Lookup.of(paragraph)
        assertTrue(q.truncated)
        assertTrue(q.term.length <= Lookup.MAX_TERM_LENGTH)
        // Cut at a word boundary, not mid-word.
        assertTrue(paragraph.startsWith(q.term))
        assertTrue(q.term.last().isLetterOrDigit())
    }

    @Test
    fun `a selection with nothing in it is nothing`() {
        listOf("", "   ", "—", "“”", "...", "\n\n").forEach {
            val q = Lookup.of(it)
            assertEquals("'$it' should be empty", Lookup.Kind.NONE, q.kind)
            assertTrue(q.isEmpty)
            assertNull(Lookup.webUrl(q))
            assertTrue(Lookup.targetsFor(q).isEmpty())
        }
    }

    @Test
    fun `a selection spanning a line break is one query`() {
        assertEquals("clocks were striking", Lookup.of("clocks were\nstriking").term)
    }

    @Test
    fun `fallback urls are built and escaped`() {
        val word = Lookup.of("vile")
        assertEquals("https://en.wiktionary.org/wiki/vile", Lookup.webUrl(word, Lookup.Target.DICTIONARY))

        val phrase = Lookup.of("Victory Mansions")
        assertEquals(
            "https://duckduckgo.com/?q=Victory%20Mansions",
            Lookup.webUrl(phrase, Lookup.Target.SEARCH)
        )
        assertTrue(Lookup.webUrl(phrase, Lookup.Target.ENCYCLOPEDIA)!!.contains("Victory%20Mansions"))
    }

    @Test
    fun `a dictionary lookup folds case but a search does not`() {
        val q = Lookup.of("Whither")
        assertEquals("https://en.wiktionary.org/wiki/whither", Lookup.webUrl(q, Lookup.Target.DICTIONARY))
        assertTrue(Lookup.webUrl(q, Lookup.Target.SEARCH)!!.contains("Whither"))
    }
}
