package com.citation.core.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyAnchorTest {

    private fun anchorFor(text: String, quote: String, prefix: String = "", suffix: String = ""): TextAnchor.Flowing {
        val start = text.indexOf(quote)
        return TextAnchor.Flowing(0, start.coerceAtLeast(0), quote, prefix, suffix)
    }

    @Test
    fun exactMatchResolvesReliably() {
        val text = "The quick brown fox jumps over the lazy dog."
        val res = FuzzyAnchor.resolve(anchorFor(text, "brown fox"), text)
        assertEquals(FuzzyAnchor.Confidence.EXACT, res.confidence)
        assertEquals("brown fox", text.substring(res.start, res.end))
        assertEquals(1.0, res.score, 0.0001)
    }

    @Test
    fun survivesAnEditToTheChapter() {
        // Author fixed a typo elsewhere and inserted a sentence; the quote itself shifted position.
        val original = "Alpha beta. The quick brown fox jumps. Gamma delta."
        val edited = "Alpha beta. Inserted new sentence here. The quick brown fox leaps. Gamma delta."
        val anchor = anchorFor(original, "The quick brown fox jumps")
        val res = FuzzyAnchor.resolve(anchor, edited)
        assertEquals(FuzzyAnchor.Confidence.FUZZY, res.confidence)
        assertTrue("score was ${res.score}", res.score >= 0.72)
        assertTrue(edited.substring(res.start, res.end).contains("brown fox"))
    }

    @Test
    fun deletedPassageOrphansRatherThanMisjumping() {
        val original = "The quick brown fox jumps over the lazy dog."
        val rewritten = "Completely different content about aardvarks and zeppelins entirely."
        val res = FuzzyAnchor.resolve(anchorFor(original, "quick brown fox"), rewritten)
        assertEquals(FuzzyAnchor.Confidence.NONE, res.confidence)
        assertEquals(null, res.matchedRange)
    }

    @Test
    fun contextDisambiguatesRepeatedQuote() {
        val text = "she said yes. Later, in the dark, she said yes again quietly."
        // Two "she said yes"; prefix/suffix should steer to the second occurrence.
        val secondStart = text.lastIndexOf("she said yes")
        val anchor = TextAnchor.Flowing(
            chapterOrdinal = 0,
            approxStart = 0, // deliberately wrong hint, favouring the first occurrence
            quote = "she said yes",
            prefix = "dark, ",
            suffix = " again"
        )
        val res = FuzzyAnchor.resolve(anchor, text)
        assertEquals(FuzzyAnchor.Confidence.EXACT, res.confidence)
        assertEquals(secondStart, res.start)
    }
}
