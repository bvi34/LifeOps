package com.utilities.app.keyboard

import com.utilities.app.keyboard.logic.Lexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only thing the keyboard remembers, and therefore the only thing worth being careful about.
 *
 * Two halves. The first is that it works — a word typed is a word suggested. The second, and the
 * one these tests exist for, is that **what it refuses to keep is refused reliably**: anything with
 * a digit in it, anything very short, anything very long. That rule is what stops a word list from
 * quietly becoming a list of order numbers, licence keys and half-typed passwords, and it is a
 * one-line change away from not holding.
 */
class LexiconTest {

    @Test
    fun `a word typed is a word suggested`() {
        val lexicon = Lexicon.EMPTY.learn("javelin").learn("javelin").learn("jam")
        assertEquals(listOf("javelin", "jam"), lexicon.suggest("ja"))
        assertEquals(2, lexicon.count("javelin"))
    }

    @Test
    fun `the most-typed word comes first, and ties break alphabetically`() {
        // Alphabetical ties matter more than they look: two equally-typed words swapping places
        // between keystrokes is the flicker that makes a suggestion strip feel untrustworthy.
        val tied = Lexicon.EMPTY.learn("beta").learn("alpha")
        assertEquals(listOf("alpha", "beta"), tied.words().map { it.first })

        val ranked = tied.learn("beta")
        assertEquals(listOf("beta", "alpha"), ranked.words().map { it.first })
    }

    @Test
    fun `the prefix itself is never offered back`() {
        val lexicon = Lexicon.EMPTY.learn("the").learn("there")
        assertEquals(listOf("there"), lexicon.suggest("the"))
    }

    @Test
    fun `an empty prefix suggests nothing`() {
        assertTrue(Lexicon.EMPTY.learn("something").suggest("").isEmpty())
    }

    @Test
    fun `suggestions ignore case`() {
        val lexicon = Lexicon.EMPTY.learn("Thursday")
        assertEquals(listOf("thursday"), lexicon.suggest("THU"))
    }

    @Test
    fun `anything that could be a secret is not a word`() {
        // The rule that does the real work. A password, an order number, a licence key and a
        // one-time code all fail it, and they fail it for the same reason: a word is letters.
        listOf("hunter2", "AB12CD", "x", "no", "123456", "a-b", "user@example.com", "p@ssw0rd")
            .forEach { assertNull("'$it' should not be stored", Lexicon.normalize(it)) }

        val lexicon = Lexicon.EMPTY.learn("hunter2").learn("123456").learn("no")
        assertEquals(0, lexicon.size)
    }

    @Test
    fun `a word is letters, and may have an apostrophe inside it`() {
        assertEquals("don't", Lexicon.normalize("don't"))
        assertEquals("thursday", Lexicon.normalize("Thursday"))
        assertEquals("thursday", Lexicon.normalize("  Thursday  "))
        // Leading and trailing apostrophes are quoting, not spelling.
        assertEquals("quoted", Lexicon.normalize("'quoted'"))
        assertNull(Lexicon.normalize("'".repeat(6)))
    }

    @Test
    fun `an absurdly long token is not a word either`() {
        assertNull(Lexicon.normalize("a".repeat(Lexicon.MAX_LENGTH + 1)))
        assertEquals("a".repeat(Lexicon.MAX_LENGTH), Lexicon.normalize("a".repeat(Lexicon.MAX_LENGTH)))
    }

    @Test
    fun `the list is capped, and it is the least-typed that go`() {
        var lexicon = Lexicon.EMPTY
        // One word everybody types, and the cap's worth of words typed once each.
        repeat(50) { lexicon = lexicon.learn("common") }
        repeat(Lexicon.MAX_WORDS + 100) { index -> lexicon = lexicon.learn("word$index".filter { it.isLetter() } + letters(index)) }
        assertTrue("the cap holds", lexicon.size <= Lexicon.MAX_WORDS)
        assertEquals("the most-typed word survives eviction", 50, lexicon.count("common"))
    }

    @Test
    fun `forgetting a word forgets it`() {
        val lexicon = Lexicon.EMPTY.learn("regret").learn("keep")
        val after = lexicon.forget("regret")
        assertEquals(0, after.count("regret"))
        assertEquals(1, after.count("keep"))
        // Forgetting something that was never there is not an error and not a new list.
        assertTrue(after === after.forget("neverthere"))
    }

    @Test
    fun `learning returns the same list when there was nothing to learn`() {
        // Identity, not equality: the store uses it to skip a file write on every keystroke that
        // typed a digit.
        val lexicon = Lexicon.EMPTY.learn("real")
        assertTrue(lexicon === lexicon.learn("x"))
    }

    @Test
    fun `the stored form is readable, and survives a round trip`() {
        val lexicon = Lexicon.EMPTY.learn("thursday").learn("thursday").learn("javelin")
        val text = lexicon.serialize()
        assertTrue("stored as plain lines", text.contains("thursday\t2"))
        val read = Lexicon.parse(text)
        assertEquals(lexicon.words(), read.words())
    }

    @Test
    fun `a corrupt line is skipped rather than fatal`() {
        val read = Lexicon.parse("good\t3\nnonsense\nbad\tnotanumber\nalso\t0\nfine\t1")
        assertEquals(3, read.count("good"))
        assertEquals(1, read.count("fine"))
        assertEquals(0, read.count("also"))
        assertEquals(2, read.size)
    }

    @Test
    fun `an empty file is an empty list`() {
        assertEquals(0, Lexicon.parse("").size)
        assertEquals(0, Lexicon.parse("\n\n").size)
    }

    @Test
    fun `the word under the cursor is whatever could still become one`() {
        // Deliberately looser than what is stored: two letters into a name, somebody should be
        // offered completions even though two letters would never be kept.
        assertEquals("th", Lexicon.wordBeforeCursor("I think th"))
        assertEquals("don't", Lexicon.wordBeforeCursor("I don't"))
        assertEquals("", Lexicon.wordBeforeCursor("finished. "))
        assertEquals("", Lexicon.wordBeforeCursor(""))
    }

    @Test
    fun `of filters what it is handed`() {
        val lexicon = Lexicon.of(mapOf("keep" to 3, "x" to 9, "n0pe" to 4))
        assertEquals(1, lexicon.size)
        assertEquals(3, lexicon.count("keep"))
        assertFalse(lexicon.words().any { it.first == "n0pe" })
    }

    /** Distinct alphabetic words, for the eviction test. */
    private fun letters(index: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        var n = index
        val builder = StringBuilder()
        repeat(4) {
            builder.append(alphabet[n % 26])
            n /= 26
        }
        return builder.toString()
    }
}
