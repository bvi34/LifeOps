package com.lifeops.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekResetTest {

    private fun closed(id: String, index: Int, used: Boolean = false, skipped: Boolean = false) =
        WeekReset.WeekInfo(id, index, isClosed = true, isSkipped = skipped, wasUsed = used)

    private fun open(id: String, index: Int) =
        WeekReset.WeekInfo(id, index, isClosed = false, isSkipped = false, wasUsed = false)

    @Test
    fun `last X weeks end right before today's week`() {
        val weeks = (1..9).map { closed("w$it", it, used = true) } + open("w10", 10)
        val plan = WeekReset.plan(weeks, currentIndex = 10)
        assertEquals(9, plan.availableCount)
        assertEquals(7..9, plan.range(3))
        assertEquals(9..9, plan.range(1))
    }

    @Test
    fun `used weeks can still be picked by hand`() {
        val weeks = listOf(closed("w1", 1, used = true), closed("w2", 2, used = true), open("w3", 3))
        val plan = WeekReset.plan(weeks, currentIndex = 3)
        assertEquals(0, plan.suggestedCount)
        assertEquals(1..2, plan.range(2))
    }

    @Test
    fun `range is clamped to the weeks that exist`() {
        val plan = WeekReset.plan(listOf(closed("w5", 5), open("w6", 6)), currentIndex = 6)
        assertEquals(1, plan.availableCount)
        assertEquals(5..5, plan.range(10))
        assertTrue(plan.range(0).isEmpty())
    }

    @Test
    fun `no past weeks means nothing to pick`() {
        val plan = WeekReset.plan(listOf(open("w1", 1)), currentIndex = 1)
        assertEquals(0, plan.availableCount)
        assertTrue(plan.range(3).isEmpty())
    }

    @Test
    fun `availability is capped`() {
        val plan = WeekReset.plan(listOf(closed("w1", 1), open("w200", 200)), currentIndex = 200)
        assertEquals(WeekReset.MAX_WEEKS, plan.availableCount)
    }

    @Test
    fun `suggestion is the quiet run right before today's week`() {
        val weeks = listOf(
            closed("w8", 8, used = true),
            closed("w9", 9), closed("w10", 10), closed("w11", 11),
            open("w12", 12)
        )
        assertEquals(3, WeekReset.plan(weeks, currentIndex = 12).suggestedCount)
    }

    @Test
    fun `stale open weeks count toward the suggestion`() {
        val weeks = listOf(closed("w5", 5, used = true), closed("w6", 6), closed("w7", 7), open("w8", 8))
        val plan = WeekReset.plan(weeks, currentIndex = 10)
        assertEquals(4, plan.suggestedCount)   // 6, 7, the open 8, and the not-yet-minted 9
        assertEquals(5, plan.availableCount)
    }

    @Test
    fun `suggestion stops at a used or already scarred week`() {
        val used = listOf(closed("w1", 1), closed("w2", 2, used = true), open("w3", 3))
        assertEquals(0, WeekReset.plan(used, currentIndex = 3).suggestedCount)
        val scarred = listOf(closed("w1", 1), closed("w2", 2, skipped = true), closed("w3", 3), open("w4", 4))
        assertEquals(1, WeekReset.plan(scarred, currentIndex = 4).suggestedCount)
    }

    @Test
    fun `note flags cleared tasks`() {
        assertEquals("True reset: hospital", WeekReset.note(" hospital ", true))
        assertEquals("True reset", WeekReset.note(null, true))
        assertEquals("travel", WeekReset.note("travel", false))
    }
}
