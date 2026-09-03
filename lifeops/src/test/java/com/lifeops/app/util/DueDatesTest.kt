package com.lifeops.app.util

import com.operations.suitekit.SuiteVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * LifeOps' half of the picker's bargain — and deliberately not the same as Health's. Next week is a
 * plan here, not a typo; what LifeOps objects to is a week that has already been closed.
 */
class DueDatesTest {

    private val weekStart = "2026-08-31"
    private val weekEnd = "2026-09-06"

    private fun check(day: String, closed: Boolean = false) =
        DueDates.check(LocalDate.parse(day), weekStart, weekEnd, closed)

    @Test
    fun `a day inside an open week is unremarkable`() {
        assertEquals(SuiteVerdict.Fine, check("2026-09-03"))
        assertEquals(SuiteVerdict.Fine, check(weekStart))
        assertEquals(SuiteVerdict.Fine, check(weekEnd))
    }

    @Test
    fun `next week is allowed — this is a planner — but it is worth saying where it goes`() {
        val verdict = check("2026-09-10")
        assertTrue(verdict is SuiteVerdict.Note)
        assertTrue(verdict.allowed)
        assertTrue(verdict.text!!.contains("Future Tasks"))
    }

    @Test
    fun `a closed week is refused, because the write would otherwise vanish`() {
        // TaskRepository.addTask returns without writing when the week is closed. Saying so at the
        // point of entry is the whole reason this rule exists.
        val verdict = check("2026-09-03", closed = true)
        assertTrue(verdict is SuiteVerdict.Refused)
        assertEquals(false, verdict.allowed)
        assertTrue(verdict.text!!.contains("already closed"))
    }

    @Test
    fun `closing a week does not close the weeks after it`() {
        val verdict = check("2026-09-10", closed = true)
        assertTrue(verdict is SuiteVerdict.Note)
        assertTrue(verdict.allowed)
    }

    @Test
    fun `before any week has been opened there is nothing to say`() {
        assertEquals(
            SuiteVerdict.Fine,
            DueDates.check(LocalDate.parse("2026-09-03"), null, null, false)
        )
    }
}
