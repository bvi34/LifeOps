package com.citation.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splitter decides what a listener can seek to, so its edges are the ones worth pinning: an
 * abbreviation that must not end a sentence, a sentence that must not run forever.
 */
class SentenceSplitterTest {

    private fun spoken(text: String, max: Int = SentenceSplitter.DEFAULT_MAX_CHARACTERS): List<String> =
        SentenceSplitter.split(text, 0, text.length, max).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `splits plain prose on its terminators`() {
        assertEquals(
            listOf("She woke early.", "The house was cold!", "Was it always?"),
            spoken("She woke early. The house was cold! Was it always?")
        )
    }

    @Test
    fun `keeps a title's period inside the sentence`() {
        assertEquals(
            listOf("Dr. Ames met Mrs. Hale at the gate.", "Neither spoke."),
            spoken("Dr. Ames met Mrs. Hale at the gate. Neither spoke.")
        )
    }

    @Test
    fun `keeps initials together`() {
        assertEquals(listOf("J. R. R. Tolkien wrote it."), spoken("J. R. R. Tolkien wrote it."))
    }

    @Test
    fun `does not split a decimal or a version number`() {
        assertEquals(listOf("It cost 4.99 and weighed 1.5 kg."), spoken("It cost 4.99 and weighed 1.5 kg."))
    }

    @Test
    fun `does not split where the next word continues in lower case`() {
        // The rule that quietly covers every abbreviation the list does not name.
        assertEquals(listOf("The rate rose 4 pct. per annum thereafter."), spoken("The rate rose 4 pct. per annum thereafter."))
    }

    @Test
    fun `takes the closing quotation mark with the sentence it closes`() {
        assertEquals(
            listOf("\"Stop there.\"", "He did not stop."),
            spoken("\"Stop there.\" He did not stop.")
        )
    }

    @Test
    fun `treats a trailing ellipsis as an ending but not a mid-sentence one`() {
        assertEquals(listOf("She waited… and waited."), spoken("She waited… and waited."))
        assertEquals(listOf("She waited…", "Nobody came."), spoken("She waited… Nobody came."))
    }

    @Test
    fun `caps a runaway sentence at a clause boundary`() {
        val text = "The clause ran on and on, past every reasonable stopping place, " +
            "gathering subordinate ideas as it went, until the voice would have run out of air."
        val parts = spoken(text, max = 80)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 80 })
        // Cut where a reader would breathe, not mid-word.
        assertTrue(parts.first().endsWith(","))
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test
    fun `caps at a space when there is no clause punctuation to use`() {
        val text = "one two three four five six seven eight nine ten eleven twelve thirteen"
        val parts = spoken(text, max = 40)
        assertTrue(parts.all { it.length <= 40 })
        assertTrue(parts.none { it.startsWith(" ") || it.endsWith(" ") })
    }

    @Test
    fun `loses no words and keeps them in order`() {
        val text = "First sentence here. Second one follows! And a third, at length, closes it?"
        val joined = spoken(text).joinToString(" ")
        assertEquals(text, joined)
    }

    @Test
    fun `returns nothing for blank input`() {
        assertEquals(emptyList<IntRange>(), SentenceSplitter.split("   \n  ", 0, 6))
        assertEquals(emptyList<IntRange>(), SentenceSplitter.split("", 0, 0))
    }

    @Test
    fun `ranges are canonical offsets into the original text`() {
        val text = "Alpha beta. Gamma delta."
        val ranges = SentenceSplitter.split(text, 0, text.length)
        assertEquals(0..10, ranges[0])
        assertEquals(12..23, ranges[1])
    }

    @Test
    fun `splits only within the range it was given`() {
        val text = "Before. Inside one. Inside two. After."
        val ranges = SentenceSplitter.split(text, 8, 31)
        assertEquals(listOf("Inside one.", "Inside two."), ranges.map { text.substring(it.first, it.last + 1) })
    }
}
