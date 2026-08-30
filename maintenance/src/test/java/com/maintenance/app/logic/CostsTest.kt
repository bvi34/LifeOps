package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CostsTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun service(daysAgo: Long, cents: Long, meter: Long? = null) =
        ServiceEntry("s$daysAgo", "a1", now - daysAgo * day, cents, meter)

    @Test
    fun `a summary totals the window, not the history`() {
        val entries = listOf(service(400, 50_000), service(30, 12_000), service(5, 8_000))

        val year = Costs.summary(entries, since = now - 365 * day)
        assertEquals(20_000L, year.totalCents)
        assertEquals(2, year.entries)

        val all = Costs.summary(entries)
        assertEquals(70_000L, all.totalCents)
        assertEquals(3, all.entries)
    }

    @Test
    fun `premiums are pro-rated across the same window as the services`() {
        val insurance = Coverage("c1", "a1", CoverageKind.INSURANCE, "Some Mutual", premiumCents = 100_00L, period = PremiumPeriod.MONTHLY)

        val halfYear = Costs.summary(
            entries = listOf(service(10, 200_00L)),
            since = now - 182 * day,
            coverages = listOf(insurance),
            now = now
        )

        // $1,200 a year, half a year of it.
        assertEquals(598_36L, halfYear.coverageCents)
        assertEquals(798_36L, halfYear.allInCents)
    }

    @Test
    fun `spend per year is refused until there is a year to divide`() {
        assertNull(Costs.perYear(listOf(service(30, 20_000)), now))
        assertNull(Costs.perYear(emptyList(), now))

        // Two years of history, $1,000 spent: $500 a year.
        val old = listOf(service(730, 50_000), service(10, 50_000))
        assertEquals(50_000L, Costs.perYear(old, now)!!)
    }

    @Test
    fun `cost per mile needs a meter that moved`() {
        val readings = listOf(
            MeterReading(now - 365 * day, 10_000),
            MeterReading(now, 22_000)
        )
        val entries = listOf(service(300, 60_000), service(30, 60_000))

        assertEquals(10.0, Costs.centsPerMeterUnit(entries, readings)!!, 0.0001)
        assertNull(Costs.centsPerMeterUnit(entries, readings.take(1)))
        assertNull(Costs.centsPerMeterUnit(emptyList(), readings))
    }

    @Test
    fun `the largest jobs are what a cost list leads with`() {
        val entries = listOf(service(300, 60_000), service(30, 250_000), service(10, 4_000))

        assertEquals(listOf(250_000L, 60_000L), Costs.largest(entries, 2).map { it.costCents })
    }
}
