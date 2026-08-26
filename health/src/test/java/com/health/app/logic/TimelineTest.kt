package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The history: everything that happened, in the order it happened, and honest about which of it was
 * written up afterwards.
 */
class TimelineTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 3, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun entry(
        id: String,
        kind: TimelineKind,
        atMillis: Long,
        recordedAtMillis: Long? = null,
        headline: String = id
    ) = TimelineEntry(id, kind, atMillis, recordedAtMillis, headline)

    @Test
    fun `days read newest first and each day reads forwards`() {
        // A list of days reads backwards — the latest is what you want first — but a single day of
        // an illness reads the way it was lived.
        val days = Timeline.build(
            TimelineFacts(
                doses = listOf(
                    entry("a", TimelineKind.DOSE, at(10, 8)),
                    entry("b", TimelineKind.DOSE, at(10, 20)),
                    entry("c", TimelineKind.DOSE, at(11, 9))
                )
            ),
            zone
        )
        assertEquals(2, days.size)
        assertEquals(listOf("c"), days[0].entries.map { it.id })
        assertEquals(listOf("a", "b"), days[1].entries.map { it.id })
    }

    @Test
    fun `the day an illness started is day one`() {
        val days = Timeline.build(
            TimelineFacts(
                readings = listOf(entry("r", TimelineKind.READING, at(12, 9))),
                episodeStartedAtMillis = at(10, 22),
                episodeTitle = "Flu"
            ),
            zone
        )
        // Newest first: the 12th is day 3, and the 10th (the episode start entry) is day 1.
        assertEquals(listOf(3, 1), days.map { it.dayNumber })
        assertEquals("Day 3", days[0].label)
    }

    @Test
    fun `without an episode there is no day number to invent`() {
        val days = Timeline.build(
            TimelineFacts(doses = listOf(entry("a", TimelineKind.DOSE, at(10, 8)))),
            zone
        )
        assertNull(days[0].dayNumber)
        assertEquals("2026-03-10", days[0].label)
    }

    @Test
    fun `the illness's own start and end are entries too`() {
        val days = Timeline.build(
            TimelineFacts(
                episodeStartedAtMillis = at(10, 22),
                episodeEndedAtMillis = at(13, 9),
                episodeTitle = "Flu"
            ),
            zone
        )
        assertEquals(2, days.size)
        assertEquals("Flu marked over", days[0].entries.single().headline)
        assertEquals("Flu started", days[1].entries.single().headline)
        // Bookends aren't records — they don't count towards "how much is in here".
        assertEquals(0, Timeline.entryCount(days))
    }

    @Test
    fun `a record written up hours later is marked as filled in`() {
        // The 2am dose, typed up over breakfast. Worth having, and worth knowing it was remembered.
        val dose = entry("d", TimelineKind.DOSE, at(10, 2), recordedAtMillis = at(10, 9))
        assertTrue(dose.wasFilledIn)
        assertEquals("written 7h later", dose.filledInLabel)
    }

    @Test
    fun `a record made at the time is not`() {
        // Finishing with the thermometer, settling the child, then opening the app is still "at the
        // time" — flagging that would attach the note to nearly everything and make it meaningless.
        val reading = entry("r", TimelineKind.READING, at(10, 2), recordedAtMillis = at(10, 2, 25))
        assertFalse(reading.wasFilledIn)
        assertNull(reading.filledInLabel)
    }

    @Test
    fun `the half-hour boundary is exclusive`() {
        val exactly = entry(
            "a", TimelineKind.DOSE, at(10, 2),
            recordedAtMillis = at(10, 2) + Timeline.FILLED_IN_AFTER_MS
        )
        val justOver = entry(
            "b", TimelineKind.DOSE, at(10, 2),
            recordedAtMillis = at(10, 2) + Timeline.FILLED_IN_AFTER_MS + 1
        )
        assertFalse(exactly.wasFilledIn)
        assertTrue(justOver.wasFilledIn)
    }

    @Test
    fun `a row from before Health tracked this is not accused of anything`() {
        // Null means "don't know when this was entered", which is not evidence either way. Flagging
        // it would be inventing a fact about how it was recorded.
        val old = entry("d", TimelineKind.DOSE, at(10, 2), recordedAtMillis = null)
        assertFalse(old.wasFilledIn)
        assertNull(old.filledInLabel)
    }

    @Test
    fun `the header counts what was filled in, and what is there at all`() {
        val days = Timeline.build(
            TimelineFacts(
                doses = listOf(
                    entry("a", TimelineKind.DOSE, at(10, 2), recordedAtMillis = at(10, 9)),
                    entry("b", TimelineKind.DOSE, at(10, 14), recordedAtMillis = at(10, 14))
                ),
                readings = listOf(entry("r", TimelineKind.READING, at(10, 15), recordedAtMillis = null)),
                episodeStartedAtMillis = at(10, 1)
            ),
            zone
        )
        assertEquals(3, Timeline.entryCount(days))
        assertEquals(1, Timeline.filledInCount(days))
    }

    @Test
    fun `simultaneous entries read in the order they happened`() {
        // A dose given "at" the same minute as the reading that prompted it: the reading first.
        val days = Timeline.build(
            TimelineFacts(
                readings = listOf(entry("r", TimelineKind.READING, at(10, 8))),
                doses = listOf(entry("d", TimelineKind.DOSE, at(10, 8))),
                careNotes = listOf(entry("c", TimelineKind.CARE, at(10, 8)))
            ),
            zone
        )
        assertEquals(listOf("r", "d", "c"), days.single().entries.map { it.id })
    }

    @Test
    fun `nothing recorded is an empty history, not a day with nothing in it`() {
        assertEquals(emptyList<TimelineDay>(), Timeline.build(TimelineFacts(), zone))
    }

    @Test
    fun `days are grouped in the reader's zone, not UTC`() {
        // 23:30 UTC on the 10th is already the 11th in Tokyo. A history that split days by UTC would
        // put a household's evening and its night on different pages.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val facts = TimelineFacts(doses = listOf(entry("a", TimelineKind.DOSE, at(10, 23, 30))))
        assertEquals("2026-03-10", Timeline.build(facts, zone).single().date.toString())
        assertEquals("2026-03-11", Timeline.build(facts, tokyo).single().date.toString())
    }
}
