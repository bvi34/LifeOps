package com.health.app.logic

import com.operations.suitekit.SuiteVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Health's half of the picker's bargain: the control offers every instant, and this decides what
 * Health makes of it. The three answers are the point — a refusal, a remark, and silence.
 */
class HealthWhenTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private val today: LocalDate = LocalDate.of(2026, 9, 3)
    private val now: Long = at(today, 14, 0)

    @Test
    fun `a moment later today is refused, and says why`() {
        val verdict = HealthWhen.check(at(today, 18), now)
        assertTrue(verdict is SuiteVerdict.Refused)
        assertEquals(false, verdict.allowed)
        assertTrue(verdict.text!!.contains("still to come"))
    }

    @Test
    fun `tomorrow is refused too`() {
        assertTrue(HealthWhen.check(at(today.plusDays(1), 9), now) is SuiteVerdict.Refused)
    }

    @Test
    fun `a minute of slack, so a slow typist's own default does not turn into a refusal`() {
        // "Now" is fixed when the dialog opens; ninety seconds of typing must not invalidate it.
        assertEquals(SuiteVerdict.Fine, HealthWhen.check(now + 30_000, now))
        assertTrue(HealthWhen.check(now + 120_000, now) is SuiteVerdict.Refused)
    }

    @Test
    fun `earlier today is simply fine — that is the ordinary case`() {
        assertEquals(SuiteVerdict.Fine, HealthWhen.check(at(today, 3), now))
        assertEquals(SuiteVerdict.Fine, HealthWhen.check(now, now))
    }

    @Test
    fun `a back-dated record is allowed, and said out loud`() {
        val verdict = HealthWhen.check(at(today.minusDays(6), 22), now)
        assertTrue(verdict is SuiteVerdict.Note)
        // Allowed: reconstructing last month's flu is what the feature is for.
        assertTrue(verdict.allowed)
        assertTrue(verdict.text!!.startsWith("Filed late"))
    }

    @Test
    fun `yesterday counts as late even when it is only a few hours back`() {
        // 1am today looking back to 11pm yesterday: two hours, but a different day, and the day is
        // what people mean by "I'm entering this late".
        val earlyToday = at(today, 1)
        assertTrue(HealthWhen.check(at(today.minusDays(1), 23), earlyToday) is SuiteVerdict.Note)
    }
}
