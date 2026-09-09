package com.finance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The forecast, tested for the property it is worth having: **the real balance on the trough day
 * will be this or better, never worse.** Everything else here is arithmetic; that sentence is the
 * product, and it only stays true because income is refused.
 */
class ForecastTest {

    private val today = LocalDate.parse("2026-09-09")

    @Test
    fun `the line falls by bills on the days they fall due`() {
        val projection = Forecast.project(
            startingCashCents = 200_000L,
            bills = listOf(bill(payee = "Rent", due = "2026-09-12", amount = 900.0)),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 10L
        )
        assertEquals(200_000L, projection.days.first().balanceCents)
        assertEquals(200_000L, projection.days.first { it.date == LocalDate.parse("2026-09-11") }.balanceCents)
        assertEquals(110_000L, projection.days.first { it.date == LocalDate.parse("2026-09-12") }.balanceCents)
        assertEquals(90_000L, projection.committedCents)
    }

    @Test
    fun `the trough is the low point, and the first day of it rather than the last`() {
        val projection = Forecast.project(
            startingCashCents = 100_000L,
            bills = listOf(bill(payee = "Rent", due = "2026-09-11", amount = 900.0)),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 5L
        )
        // The balance is $100 from the 11th onwards; the day worth reporting is when it got there.
        assertEquals(LocalDate.parse("2026-09-11"), projection.trough!!.date)
        assertEquals(10_000L, projection.trough!!.balanceCents)
    }

    @Test
    fun `no income is ever projected, so the line only falls`() {
        val projection = Forecast.project(
            startingCashCents = 300_000L,
            bills = listOf(bill(payee = "Rent", due = "2026-09-20", amount = 900.0)),
            dailyBurnCents = 5_000L,
            today = today,
            horizonDays = 30L
        )
        // A paycheque would make a much prettier line and a much less useful promise.
        val balances = projection.days.map { it.balanceCents }
        assertEquals(balances.sorted().reversed(), balances)
        assertEquals(projection.days.last(), projection.trough)
    }

    @Test
    fun `today is charged its bills but not a day of ordinary spending`() {
        // By the time anybody looks at this screen most of today's spending has happened. Charging
        // it again would make the app look alarming every single morning.
        val projection = Forecast.project(
            startingCashCents = 100_000L,
            bills = emptyList(),
            dailyBurnCents = 5_000L,
            today = today,
            horizonDays = 2L
        )
        assertEquals(100_000L, projection.days[0].balanceCents)
        assertEquals(95_000L, projection.days[1].balanceCents)
        assertEquals(90_000L, projection.days[2].balanceCents)
    }

    @Test
    fun `an overdue bill lands on today rather than being dropped`() {
        // It is still money that has to go out. Dropping it would show cash the household has
        // already committed.
        val projection = Forecast.project(
            startingCashCents = 100_000L,
            bills = listOf(bill(payee = "Missed", due = "2026-09-02", amount = 400.0)),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 5L
        )
        assertEquals(60_000L, projection.days.first().balanceCents)
    }

    @Test
    fun `a paid bill is not taken out twice`() {
        // The money has gone; the balance passed in already reflects it.
        val projection = Forecast.project(
            startingCashCents = 100_000L,
            bills = listOf(bill(payee = "Rent", due = "2026-09-12", amount = 900.0, paidOn = "2026-09-08")),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 10L
        )
        assertEquals(100_000L, projection.days.last().balanceCents)
        assertEquals(0L, projection.committedCents)
    }

    @Test
    fun `bills past the horizon are not projected against`() {
        val projection = Forecast.project(
            startingCashCents = 100_000L,
            bills = listOf(bill(payee = "Later", due = "2026-11-01", amount = 900.0)),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 10L
        )
        assertEquals(0L, projection.committedCents)
        assertFalse(projection.overdrawn)
    }

    @Test
    fun `a shortfall names the day the line goes under and the amount to cover it`() {
        val projection = Forecast.project(
            startingCashCents = 50_000L,
            bills = listOf(
                bill(payee = "Rent", due = "2026-09-12", amount = 900.0, id = "rent"),
                bill(payee = "Card", due = "2026-09-14", amount = 200.0, id = "card")
            ),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 10L
        )
        assertTrue(projection.overdrawn)
        assertEquals(LocalDate.parse("2026-09-12"), projection.shortfall!!.date)

        val (amount, by) = Forecast.shortfallToCover(projection)!!
        // Down $400 on the 12th and $600 by the 14th: you need $600 in, and by the 12th.
        assertEquals(60_000L, amount)
        assertEquals(LocalDate.parse("2026-09-12"), by)
    }

    @Test
    fun `a floor above zero is the more useful alarm`() {
        val projection = Forecast.project(
            startingCashCents = 100_000L,
            bills = listOf(bill(payee = "Rent", due = "2026-09-12", amount = 900.0)),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 10L
        )
        assertFalse("it never goes under zero", projection.overdrawn)
        // But it goes under a $500 buffer, which is the thing somebody actually wants warning about.
        val (amount, by) = Forecast.shortfallToCover(projection, floorCents = 50_000L)!!
        assertEquals(40_000L, amount)
        assertEquals(LocalDate.parse("2026-09-12"), by)
        assertNull(Forecast.shortfallToCover(projection, floorCents = 0L))
    }

    @Test
    fun `the headline is worded as a floor, never as a prediction`() {
        val healthy = Forecast.project(
            startingCashCents = 200_000L,
            bills = listOf(bill(payee = "Rent", due = "2026-09-12", amount = 900.0)),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 10L
        )
        val line = healthy.headline(today)
        assertTrue(line, line.startsWith("At worst $1,100 in 3 days"))
        assertTrue("the caveat is the point", line.contains("before anything else comes in"))
    }

    @Test
    fun `a shortfall headline says how short, not how much is left`() {
        val short = Forecast.project(
            startingCashCents = 50_000L,
            bills = listOf(bill(payee = "Rent", due = "2026-09-10", amount = 900.0)),
            dailyBurnCents = 0L,
            today = today,
            horizonDays = 10L
        )
        assertTrue(short.headline(today), short.headline(today).startsWith("Short by $400 tomorrow"))
    }

    @Test
    fun `a zero horizon produces nothing rather than a nonsense line`() {
        val empty = Forecast.project(100_000L, emptyList(), 0L, today, horizonDays = 0L)
        assertTrue(empty.days.isEmpty())
        assertNull(empty.trough)
        assertEquals("Nothing scheduled to project against.", empty.headline(today))
    }
}
