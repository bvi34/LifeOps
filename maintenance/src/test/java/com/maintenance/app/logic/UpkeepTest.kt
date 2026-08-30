package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpkeepTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun plan(
        everyDays: Int? = null,
        everyMeter: Long? = null,
        lastDoneAt: Long? = null,
        lastDoneMeter: Long? = null,
        createdAt: Long = now,
        active: Boolean = true
    ) = UpkeepPlan(
        id = "p1",
        assetId = "a1",
        title = "Oil change",
        everyDays = everyDays,
        everyMeter = everyMeter,
        lastDoneAt = lastDoneAt,
        lastDoneMeter = lastDoneMeter,
        createdAt = createdAt,
        active = active
    )

    @Test
    fun `a plan with no interval is a note, not an overdue job`() {
        val verdict = Upkeep.evaluate(plan(), now)

        assertEquals(DueStatus.DORMANT, verdict.status)
        assertTrue(verdict.summary.contains("No schedule"))
    }

    @Test
    fun `a paused plan says so and nothing else`() {
        assertEquals(DueStatus.DORMANT, Upkeep.evaluate(plan(everyDays = 90, active = false), now).status)
    }

    @Test
    fun `a plan that has never been done starts its clock the day it was written`() {
        val verdict = Upkeep.evaluate(plan(everyDays = 90, createdAt = now), now)

        assertEquals(DueStatus.SCHEDULED, verdict.status)
        assertEquals(now + 90 * day, verdict.dueAt)
        assertEquals("Due in 3 months", verdict.summary)
    }

    @Test
    fun `overdue is counted from the last time it was actually done`() {
        val verdict = Upkeep.evaluate(plan(everyDays = 90, lastDoneAt = now - 100 * day), now)

        assertEquals(DueStatus.OVERDUE, verdict.status)
        assertEquals(-10, verdict.daysRemaining)
        assertEquals("Overdue by 10 days", verdict.summary)
    }

    @Test
    fun `the fortnight before it falls due is due soon`() {
        val verdict = Upkeep.evaluate(plan(everyDays = 90, lastDoneAt = now - 80 * day), now)

        assertEquals(DueStatus.DUE_SOON, verdict.status)
        assertEquals(10, verdict.daysRemaining)
        assertEquals("Due in 10 days", verdict.summary)
    }

    @Test
    fun `a mileage interval with nothing to measure from asks for a baseline`() {
        val meter = MeterState(MeterUnit.MILES, current = 42_000, perDay = 30.0)
        val verdict = Upkeep.evaluate(plan(everyMeter = 5_000), now, meter)

        assertEquals(DueStatus.NEEDS_BASELINE, verdict.status)
        assertTrue(verdict.summary.contains("log one service"))
    }

    @Test
    fun `whichever comes first wins, and says which it was`() {
        // Serviced 30 days ago at 10,000 miles; 40 miles a day since. The mileage leg lands in
        // five days, the six-month leg in five months.
        val meter = MeterState(MeterUnit.MILES, current = 11_800, perDay = 40.0)
        val verdict = Upkeep.evaluate(
            plan(everyDays = 180, everyMeter = 2_000, lastDoneAt = now - 30 * day, lastDoneMeter = 10_000),
            now,
            meter
        )

        assertEquals(DueStatus.DUE_SOON, verdict.status)
        assertTrue(verdict.byMeter)
        assertEquals(12_000L, verdict.dueMeter)
        assertEquals(200L, verdict.meterRemaining)
        assertEquals("Due in 200 mi", verdict.summary)
        assertEquals(now + 5 * day, verdict.dueAt)
    }

    @Test
    fun `a mileage interval already passed is overdue in miles`() {
        val meter = MeterState(MeterUnit.MILES, current = 12_200, perDay = 40.0)
        val verdict = Upkeep.evaluate(
            plan(everyDays = 180, everyMeter = 2_000, lastDoneAt = now - 30 * day, lastDoneMeter = 10_000),
            now,
            meter
        )

        assertEquals(DueStatus.OVERDUE, verdict.status)
        assertEquals("Overdue by 200 mi", verdict.summary)
    }

    @Test
    fun `without a rate, the last tenth of a mileage interval still counts as soon`() {
        val noRate = MeterState(MeterUnit.MILES, current = 11_800, perDay = null)
        val verdict = Upkeep.evaluate(
            plan(everyMeter = 2_000, lastDoneMeter = 10_000, lastDoneAt = now - 30 * day),
            now,
            noRate
        )

        assertEquals(DueStatus.DUE_SOON, verdict.status)
        assertEquals("Due in 200 mi", verdict.summary)
        // Nothing to date it with, so nothing is claimed about when.
        assertEquals(null, verdict.dueAt)
    }

    @Test
    fun `the date leg governs when the miles are a long way off`() {
        val meter = MeterState(MeterUnit.MILES, current = 10_100, perDay = 5.0)
        val verdict = Upkeep.evaluate(
            plan(everyDays = 180, everyMeter = 5_000, lastDoneAt = now - 175 * day, lastDoneMeter = 10_000),
            now,
            meter
        )

        assertEquals(DueStatus.DUE_SOON, verdict.status)
        assertTrue("the date leg should govern", !verdict.byMeter)
        assertEquals("Due in 5 days", verdict.summary)
    }

    @Test
    fun `plans that cannot be dated sort after those that can`() {
        val dated = DueVerdict(DueStatus.SCHEDULED, dueAt = now, summary = "")
        val undated = DueVerdict(DueStatus.SCHEDULED, dueAt = null, summary = "")

        assertTrue(Upkeep.sortKey(dated) < Upkeep.sortKey(undated))
    }
}
