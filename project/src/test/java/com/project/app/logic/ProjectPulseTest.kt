package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPulseTest {

    private fun pulse(
        kind: ProjectKind = ProjectKind.WRITING,
        totals: OutlineTotals = OutlineTotals.EMPTY,
        board: BoardStats = BoardStats(0, 0, 0, emptyList()),
        docs: Int = 0,
        lore: Int = 0,
        events: Int = 0
    ) = ProjectPulse(kind, totals, board, docs, lore, events, brokenLinks = 0, updatedAt = 0L)

    @Test
    fun `an empty project says so rather than showing a row of zeroes`() {
        val empty = ProjectPulse.empty(ProjectKind.WRITING)

        assertTrue(empty.isEmpty)
        assertEquals("Nothing in it yet", empty.headline)
        assertNull(empty.wordProgress)
    }

    @Test
    fun `a manuscript reads in words, pieces and cards`() {
        val headline = pulse(
            kind = ProjectKind.WRITING,
            totals = OutlineTotals(targetWords = 80_000, actualWords = 34_200, pieces = 40, piecesComplete = 12, cut = 2),
            board = BoardStats(total = 9, done = 6, inFlight = 3, overLimitColumns = emptyList())
        ).headline

        assertEquals("34,200 of 80,000 words · 12 of 40 scenes · 3 in flight", headline)
    }

    @Test
    fun `a project with no word target is not reported as zero of zero`() {
        val headline = pulse(
            kind = ProjectKind.SOFTWARE,
            totals = OutlineTotals(targetWords = 0, actualWords = 0, pieces = 5, piecesComplete = 1, cut = 0),
            board = BoardStats(total = 4, done = 4, inFlight = 0, overLimitColumns = emptyList())
        ).headline

        assertEquals("1 of 5 tasks · board clear", headline)
    }

    @Test
    fun `words are only claimed for the kinds actually measured in them`() {
        val totals = OutlineTotals(targetWords = 1_000, actualWords = 500, pieces = 1, piecesComplete = 0, cut = 0)

        assertEquals(0.5f, pulse(kind = ProjectKind.WRITING, totals = totals).wordProgress!!, 0.0001f)
        assertNull(pulse(kind = ProjectKind.SOFTWARE, totals = totals).wordProgress)
        assertTrue(pulse(kind = ProjectKind.SOFTWARE, totals = totals).headline.contains("0 of 1 task"))
    }

    @Test
    fun `a project that is only documents and lore still says what it holds`() {
        val headline = pulse(kind = ProjectKind.RESEARCH, docs = 4, lore = 1, events = 2).headline

        assertEquals("4 docs · 1 lore entry · 2 timeline events", headline)
    }

    @Test
    fun `counts read at a glance`() {
        assertEquals("0", ProjectPulse.count(0))
        assertEquals("999", ProjectPulse.count(999))
        assertEquals("1,000", ProjectPulse.count(1_000))
        assertEquals("34,200", ProjectPulse.count(34_200))
        assertEquals("1,234,567", ProjectPulse.count(1_234_567))
        assertEquals("-1,200", ProjectPulse.count(-1_200))
    }

    @Test
    fun `plurals`() {
        assertEquals("doc", ProjectPulse.plural(1, "doc"))
        assertEquals("docs", ProjectPulse.plural(2, "doc"))
        assertEquals("entries", ProjectPulse.plural(0, "entry", "entries"))
    }
}
