package com.utilities.app.keyboard

import com.utilities.app.keyboard.logic.Corrections
import com.utilities.app.keyboard.logic.Lexicon
import com.utilities.app.keyboard.logic.Vocabulary
import com.utilities.app.keyboard.logic.WordBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Autocorrect, which is mostly a test of when it does nothing.
 *
 * The cases below are in two halves on purpose. The first half is the feature: the four ways a word
 * comes out wrong and what each becomes. The second half is the reason the feature is tolerable —
 * every kind of word the keyboard refuses to touch, because a correction nobody wanted is read back
 * as what they meant to say, by them and by whoever they sent it to.
 *
 * Each case builds the dictionary it needs rather than reading the shipped one, so that what is
 * being pinned is the rule and not the contents of a word list somebody may regenerate.
 * `WordBookAssetTest` is where the real file is checked.
 */
class CorrectionsTest {

    private fun book(vararg entries: Pair<String, Int>): WordBook =
        WordBook.parse(entries.sortedBy { it.first }.joinToString("\n") { (word, tier) -> "$tier$word" })

    private fun vocabulary(book: WordBook, typed: Lexicon = Lexicon.EMPTY) = Vocabulary(typed, book)

    private fun correct(
        word: String,
        book: WordBook,
        typed: Lexicon = Lexicon.EMPTY,
        startsSentence: Boolean = false
    ): String? = Corrections.of(word, startsSentence, vocabulary(book, typed))?.to

    // ---------------------------------------------------------------------------------------
    // What it fixes
    // ---------------------------------------------------------------------------------------

    @Test
    fun `two letters the wrong way round`() {
        assertEquals("the", correct("teh", book("the" to 1, "ten" to 1)))
    }

    @Test
    fun `a missing apostrophe, which is the letter people leave out on purpose`() {
        assertEquals("don't", correct("dont", book("don't" to 1, "font" to 2)))
        assertEquals("you're", correct("youre", book("you're" to 1)))
    }

    @Test
    fun `a doubled letter typed once, or once too often`() {
        val words = book("hello" to 1)
        assertEquals("hello", correct("helo", words))
        assertEquals("hello", correct("helllo", words))
    }

    @Test
    fun `the key next door`() {
        assertEquals("world", correct("worls", book("world" to 1)))
    }

    @Test
    fun `a word this household has typed is what it is corrected towards`() {
        val mine = Lexicon.EMPTY.learn("wetherby").learn("wetherby")
        assertEquals("wetherby", correct("wetherbt", book("weather" to 1), typed = mine))
    }

    @Test
    fun `a capital at the start of a sentence is a new sentence, and comes back capitalised`() {
        assertEquals("The", correct("Teh", book("the" to 1), startsSentence = true))
        assertEquals("THE", correct("TEH", book("the" to 1), startsSentence = true))
    }

    // ---------------------------------------------------------------------------------------
    // What it refuses — which is the part that makes the rest bearable
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a word the dictionary knows is never touched`() {
        assertNull(correct("the", book("the" to 1)))
        assertNull(correct("cant", book("cant" to 2, "can't" to 1)))
    }

    @Test
    fun `a word this household has typed is never touched, whatever the dictionary thinks`() {
        val mine = Lexicon.EMPTY.learn("lifeops")
        assertNull(correct("lifeops", book("lifers" to 1), typed = mine))
    }

    @Test
    fun `a capitalised word anywhere but the start of a sentence is somebody's name`() {
        assertNull(correct("Teh", book("the" to 1), startsSentence = false))
    }

    @Test
    fun `even at the start of a sentence, a capitalised word is only corrected cheaply`() {
        // `Ada` is one far-away letter from `Add`, and is a person. `Teh` is two letters swapped.
        assertNull(correct("Ada", book("add" to 1), startsSentence = true))
        assertEquals("The", correct("Teh", book("the" to 1), startsSentence = true))
    }

    @Test
    fun `a capital inside the word is somebody being deliberate`() {
        assertNull(correct("McGrath", book("mcgrath" to 1, "migrate" to 1)))
        assertNull(correct("iPhone", book("phone" to 1)))
    }

    @Test
    fun `a word too short to be sure about`() {
        assertNull(correct("og", book("of" to 1, "on" to 1)))
    }

    @Test
    fun `the long tail of the dictionary is recognised but is never a destination`() {
        // `alto` is a word, so it is never corrected; it is also not a word anybody is likely to
        // have been reaching for, so `alot` is left alone rather than turned into it.
        val words = book("alto" to 3, "lot" to 1, "slot" to 2)
        assertNull(correct("alto", words))
        assertNull(correct("alot", words))
    }

    @Test
    fun `a letter from the other side of the keyboard only reaches a common word`() {
        assertEquals("separate", correct("seperate", book("separate" to 1)))
        assertNull(correct("seperate", book("separate" to 2)))
    }

    @Test
    fun `the first letter of a word is not rewritten`() {
        assertNull(correct("kat", book("cat" to 1)))
        assertNull(correct("sog", book("dog" to 1)))
    }

    @Test
    fun `unless the first two letters were swapped, which is a slip rather than a spelling`() {
        assertEquals("the", correct("hte", book("the" to 1)))
    }

    @Test
    fun `a word this household has typed can afford even a first letter`() {
        val mine = Lexicon.EMPTY.learn("dog").learn("dog").learn("dog").learn("dog").learn("dog")
        assertEquals("dog", correct("sog", book(), typed = mine))
    }

    @Test
    fun `a plural possessive is left alone whenever the stem is a word`() {
        assertNull(correct("dogs'", book("dogs" to 1, "dog's" to 1)))
    }

    @Test
    fun `a word with no near neighbour is left exactly as it is`() {
        assertNull(correct("asdfgh", book("the" to 1, "hello" to 1, "world" to 1)))
        assertNull(correct("zzzzzz", book("the" to 1)))
    }

    @Test
    fun `nothing at all happens without a dictionary or a learned word`() {
        assertNull(correct("teh", WordBook.EMPTY))
    }
}
