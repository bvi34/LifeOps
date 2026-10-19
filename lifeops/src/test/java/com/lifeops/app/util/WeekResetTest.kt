package com.lifeops.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekResetTest {

    private fun closed(id: String, index: Int, used: Boolean = false, skipped: Boolean = false) =
        WeekReset.WeekInfo(id, index, isClosed = true, isSkipped = skipped, wasUsed = used)

    private fun open(id: String, index: Int) =
        WeekReset.WeekInfo(id, index, isClosed = false, isSkipped = false, wasUsed = false)

    @Test
    fun `stale open week plus every week up to today is closed`() {
        // Open week is 10, today is 14 → weeks 10..13 get closed and scarred.
        val plan = WeekReset.plan(listOf(closed("w9", 9, used = true), open("w10", 10)), currentIndex = 14)
        assertEquals(4, plan.weeksToClose)
        assertTrue(plan.closedWeekIds.isEmpty())
        assertEquals(4, plan.scarCount)
        assertEquals(10, plan.firstIndex)
        assertEquals(13, plan.lastIndex)
    }

    @Test
    fun `weeks auto-closed during the gap are scarred, today's week stays open`() {
        val weeks = listOf(
            closed("w8", 8, used = true),
            closed("w9", 9), closed("w10", 10), closed("w11", 11),
            open("w12", 12)
        )
        val plan = WeekReset.plan(weeks, currentIndex = 12)
        assertEquals(listOf("w9", "w10", "w11"), plan.closedWeekIds)
        assertEquals(0, plan.weeksToClose)
        assertEquals(3, plan.scarCount)
        assertEquals(9, plan.firstIndex)
        assertEquals(11, plan.lastIndex)
    }

    @Test
    fun `untouched closed weeks and a stale open week combine`() {
        val weeks = listOf(closed("w5", 5, used = true), closed("w6", 6), closed("w7", 7), open("w8", 8))
        val plan = WeekReset.plan(weeks, currentIndex = 10)
        assertEquals(listOf("w6", "w7"), plan.closedWeekIds)
        assertEquals(2, plan.weeksToClose)
        assertEquals(4, plan.scarCount)
        assertEquals(6, plan.firstIndex)
        assertEquals(9, plan.lastIndex)
    }

    @Test
    fun `nothing to do when last week was used`() {
        val plan = WeekReset.plan(listOf(closed("w1", 1, used = true), open("w2", 2)), currentIndex = 2)
        assertEquals(0, plan.scarCount)
        assertNull(plan.firstIndex)
    }

    @Test
    fun `an old quiet stretch followed by a used week is not this absence`() {
        val weeks = listOf(closed("w1", 1), closed("w2", 2), closed("w3", 3, used = true), open("w4", 4))
        assertEquals(0, WeekReset.plan(weeks, currentIndex = 4).scarCount)
    }

    @Test
    fun `already skipped weeks are never scarred twice`() {
        val weeks = listOf(closed("w1", 1), closed("w2", 2, skipped = true), closed("w3", 3, skipped = true), open("w4", 4))
        assertEquals(0, WeekReset.plan(weeks, currentIndex = 4).scarCount)
    }

    @Test
    fun `note flags a true reset`() {
        assertEquals("True reset: hospital", WeekReset.note(" hospital ", true))
        assertEquals("True reset", WeekReset.note(null, true))
        assertEquals("travel", WeekReset.note("travel", false))
    }
}
