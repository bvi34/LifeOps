package com.utilities.app.keyboard

import com.utilities.app.keyboard.logic.Lexicon
import com.utilities.app.keyboard.logic.Vocabulary
import com.utilities.app.keyboard.logic.WordBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two lists as one, and the order between them.
 *
 * The ordering is the whole class: a word this household has typed beats every word in the
 * dictionary, at suggesting and at correcting alike. That is what keeps a keyboard from arguing
 * with somebody about their own surname.
 */
class VocabularyTest {

    private val english = WordBook.parse("1life\n1lift\n1the\n2lifting")
    private val typed = Lexicon.EMPTY.learn("lifeops").learn("lifeops").learn("wetherby")
    private val vocabulary = Vocabulary(typed, english)

    @Test
    fun `a word typed once outranks the commonest word in the language`() {
        assertTrue(vocabulary.weight("wetherby") > vocabulary.weight("the"))
        assertTrue(vocabulary.weight("lifeops") > vocabulary.weight("wetherby"))
    }

    @Test
    fun `how common a dictionary word is comes through as its weight`() {
        assertEquals(Vocabulary.COMMON, vocabulary.weight("life"))
        assertEquals(Vocabulary.ORDINARY, vocabulary.weight("lifting"))
        assertEquals(0, vocabulary.weight("nonsense"))
    }

    @Test
    fun `a word from either list is a word it knows`() {
        assertTrue(vocabulary.knows("the"))
        assertTrue(vocabulary.knows("lifeops"))
        assertFalse(vocabulary.knows("nonsense"))
    }

    @Test
    fun `suggestions put this household's words first`() {
        assertEquals(listOf("lifeops", "life", "lift"), vocabulary.suggest("lif", 3))
    }

    @Test
    fun `the dictionary carries the strip on its own until anything has been learned`() {
        assertEquals(listOf("life", "lift", "lifting"), Vocabulary(Lexicon.EMPTY, english).suggest("lif", 3))
    }

    @Test
    fun `a keyboard that has learned nothing and loaded nothing suggests nothing`() {
        assertEquals(emptyList<String>(), Vocabulary().suggest("lif", 3))
        assertFalse(Vocabulary().knows("the"))
    }

    @Test
    fun `the typographer's apostrophe and the keyboard's are the same word`() {
        val book = WordBook.parse("1don't")
        assertTrue(Vocabulary(Lexicon.EMPTY, book).knows("don’t"))
        assertTrue(Vocabulary(Lexicon.EMPTY, book).knows("DON'T"))
    }
}
