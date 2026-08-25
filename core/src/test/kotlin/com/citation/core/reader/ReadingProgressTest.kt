package com.citation.core.reader

import com.citation.core.model.Book
import com.citation.core.model.BookMetadata
import com.citation.core.model.Chapter
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress measured in characters rather than chapters, and a time estimate learned from the
 * reader's own honest engaged time. The tests that matter are the ones where a number would be a
 * lie: an uneven book, a jump rather than reading, a first page with nothing observed yet.
 */
class ReadingProgressTest {

    /** A deliberately uneven book: three short front-matter chapters, then the real ones. */
    private val uneven = Book(
        key = null,
        metadata = BookMetadata(title = "T", author = null, source = SourceType.EPUB),
        chapters = listOf(
            "Foreword" to 100,
            "Preface" to 100,
            "A Note on the Text" to 100,
            "One" to 10_000,
            "Two" to 10_000
        ).mapIndexed { i, (title, length) ->
            Chapter(ordinal = i, title = title, sourceRef = "c$i", text = "x".repeat(length))
        }
    )

    @Test
    fun `progress counts characters, not chapters`() {
        // Three of five chapters in — but only 300 of 20,300 characters.
        val position = ReadingProgress.at(uneven, chapterOrdinal = 3, charOffset = 0)
        assertEquals(300, position.charactersRead)
        assertEquals(20_300, position.charactersTotal)
        assertEquals(1, position.percent)
    }

    @Test
    fun `the offset within a chapter counts too`() {
        val position = ReadingProgress.at(uneven, chapterOrdinal = 3, charOffset = 5_000)
        assertEquals(5_300, position.charactersRead)
        assertEquals(26, position.percent)
        assertEquals(0.5f, position.chapterFraction)
    }

    @Test
    fun `percent never rounds up to a hundred before the end`() {
        val almost = ReadingProgress.at(uneven, chapterOrdinal = 4, charOffset = 9_999)
        assertEquals(99, almost.percent)
        val done = ReadingProgress.at(uneven, chapterOrdinal = 4, charOffset = 10_000)
        assertEquals(100, done.percent)
    }

    @Test
    fun `an empty book reads zero rather than dividing by nothing`() {
        val empty = Book(
            key = null,
            metadata = BookMetadata("T", null, SourceType.EPUB),
            chapters = emptyList()
        )
        val position = ReadingProgress.at(empty, 0, 0)
        assertEquals(0, position.percent)
        assertEquals(0f, position.fraction)
    }

    @Test
    fun `an out of range position is clamped rather than throwing`() {
        val position = ReadingProgress.at(uneven, chapterOrdinal = 99, charOffset = 99_999)
        assertEquals(4, position.chapterOrdinal)
        assertEquals(10_000, position.charOffset)
        assertEquals(100, position.percent)
    }

    @Test
    fun `characters left in the chapter is what a chapter estimate measures`() {
        assertEquals(7_500, ReadingProgress.charactersLeftInChapter(uneven, 3, 2_500))
        assertEquals(0, ReadingProgress.charactersLeftInChapter(uneven, 3, 99_999))
        assertEquals(0, ReadingProgress.charactersLeftInChapter(uneven, 99, 0))
    }

    // --- Pace ------------------------------------------------------------------------------------

    @Test
    fun `nothing is claimed before there is evidence`() {
        val fresh = ReadingPace()
        assertNull(fresh.charactersPerMinute)
        assertFalse(fresh.confident)
        assertNull(fresh.millisFor(10_000))
        assertNull(TimeLeft.label(fresh.millisFor(10_000)))
    }

    @Test
    fun `one page is not enough evidence`() {
        val page = ReadingPace().observe(characters = 1_500, millis = 60_000)
        assertNotNull(page.charactersPerMinute)
        assertFalse("a single page should not license an estimate", page.confident)
    }

    @Test
    fun `pace becomes confident after real reading and reflects it`() {
        // 1,500 characters a minute, sustained over ten minutes.
        var pace = ReadingPace()
        repeat(10) { pace = pace.observe(characters = 1_500, millis = 60_000) }
        assertTrue(pace.confident)
        assertEquals(1_500.0, pace.charactersPerMinute!!, 1.0)
    }

    @Test
    fun `a jump is not a reading sample`() {
        var pace = ReadingPace()
        repeat(10) { pace = pace.observe(1_500, 60_000) }
        val before = pace.charactersPerMinute!!
        // Jumping to the last chapter "covers" 200k characters in four seconds.
        val after = pace.observe(characters = 200_000, millis = 4_000)
        assertEquals(before, after.charactersPerMinute!!, 0.001)
    }

    @Test
    fun `a stalled sample is ignored too`() {
        var pace = ReadingPace()
        repeat(10) { pace = pace.observe(1_500, 60_000) }
        val before = pace.charactersPerMinute!!
        // Ten characters over five minutes is not a reading rate.
        assertEquals(before, pace.observe(10, 300_000).charactersPerMinute!!, 0.001)
    }

    @Test
    fun `nonsense samples are dropped`() {
        assertEquals(ReadingPace(), ReadingPace().observe(0, 1000))
        assertEquals(ReadingPace(), ReadingPace().observe(100, 0))
        assertEquals(ReadingPace(), ReadingPace().observe(-5, -5))
    }

    @Test
    fun `a genuine change of pace is followed rather than averaged away forever`() {
        var pace = ReadingPace()
        // A long stretch of fast reading — a novel.
        repeat(400) { pace = pace.observe(1_800, 60_000) }
        val novel = pace.charactersPerMinute!!
        // Then a dense technical book, read at a third the speed, for a long while.
        repeat(200) { pace = pace.observe(600, 60_000) }
        val technical = pace.charactersPerMinute!!
        assertTrue("estimate should have moved toward the slower pace", technical < novel * 0.75)
    }

    @Test
    fun `time for a stretch of text follows the observed pace`() {
        var pace = ReadingPace()
        repeat(10) { pace = pace.observe(1_500, 60_000) }
        // 15,000 characters at 1,500 a minute is ten minutes.
        val estimate = pace.millisFor(15_000)!!
        assertTrue("expected about ten minutes, got ${estimate}ms", estimate in 595_000..605_000)
        assertEquals(0L, pace.millisFor(0))
    }

    // --- Phrasing --------------------------------------------------------------------------------

    @Test
    fun `time left reads as an orientation, not a countdown`() {
        assertEquals("4 min left", TimeLeft.label(4L * 60_000))
        assertEquals("under a minute left", TimeLeft.label(10_000))
        assertEquals("1 hr left", TimeLeft.label(60L * 60_000))
        assertEquals("1 hr 20 min left", TimeLeft.label(80L * 60_000))
        assertEquals("2 hr 1 min left", TimeLeft.label(121L * 60_000))
        assertNull(TimeLeft.label(null))
    }

    @Test
    fun `the chapter estimate says which chapter it means`() {
        assertEquals("6 min left in chapter", TimeLeft.inChapter(6L * 60_000))
        assertNull(TimeLeft.inChapter(null))
    }
}
