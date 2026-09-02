package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two questions an asset page structurally cannot answer: what all of this costs, and which of
 * these things is eating the money.
 */
class LedgerTest {

    private val day = Upkeep.DAY_MILLIS
    private val now = 1_700_000_000_000L
    private val yearAgo = now - 365 * day

    private fun asset(
        id: String,
        name: String = id,
        worth: Long? = null,
        owed: Long = 0L,
        archived: Boolean = false
    ) = LedgerAsset(id, name, AssetKind.VEHICLE, worth, owed, archived)

    private fun work(
        assetId: String,
        cents: Long,
        daysAgo: Long,
        vendor: String? = null,
        id: String = "$assetId-$daysAgo-$cents"
    ) = ServiceEntry(id, assetId, now - daysAgo * day, cents, vendor = vendor)

    @Test
    fun `the register's costs, biggest first`() {
        val ledger = Ledgers.of(
            assets = listOf(asset("truck"), asset("mower"), asset("house")),
            entries = listOf(
                work("truck", 40_000, daysAgo = 10),
                work("truck", 12_000, daysAgo = 200),
                work("mower", 6_000, daysAgo = 30)
            ),
            coverages = emptyMap(),
            since = yearAgo,
            now = now
        )

        assertEquals(listOf("truck", "mower"), ledger.spend.map { it.assetId })
        assertEquals(52_000L, ledger.spend.first().serviceCents)
        assertEquals(58_000L, ledger.serviceCents)
        // A house that cost nothing this year is not a line saying zero.
        assertTrue(ledger.spend.none { it.assetId == "house" })
    }

    @Test
    fun `the window is a window - older work is history, not this year`() {
        val entries = listOf(work("truck", 40_000, daysAgo = 10), work("truck", 99_000, daysAgo = 400))

        val year = Ledgers.of(listOf(asset("truck")), entries, emptyMap(), since = yearAgo, now = now)
        val ever = Ledgers.of(listOf(asset("truck")), entries, emptyMap(), since = null, now = now)

        assertEquals(40_000L, year.serviceCents)
        assertEquals(139_000L, ever.serviceCents)
    }

    @Test
    fun `premiums are annualised then pro-rated, the same way one asset's page does it`() {
        val cover = Coverage(
            id = "c1",
            assetId = "truck",
            kind = CoverageKind.INSURANCE,
            provider = "Someone",
            premiumCents = 10_000,
            period = PremiumPeriod.MONTHLY
        )

        val half = Ledgers.of(
            assets = listOf(asset("truck")),
            entries = emptyList(),
            coverages = mapOf("truck" to listOf(cover)),
            since = now - 182 * day,
            now = now
        )

        // $100 a month is $1,200 a year; half a year of it is $600, near enough to the day.
        assertEquals(59_836L, half.coverageCents)
        assertEquals(half.coverageCents, half.allInCents)
    }

    @Test
    fun `what you sold still cost you, but is not worth or owed anything now`() {
        val ledger = Ledgers.of(
            assets = listOf(
                asset("truck", worth = 1_500_000, owed = 800_000),
                asset("saab", worth = 200_000, owed = 50_000, archived = true)
            ),
            entries = listOf(work("truck", 40_000, daysAgo = 10), work("saab", 25_000, daysAgo = 60)),
            coverages = emptyMap(),
            since = yearAgo,
            now = now
        )

        // Spend is history and history includes the car you had until March.
        assertEquals(65_000L, ledger.serviceCents)
        assertTrue(ledger.spend.any { it.assetId == "saab" && it.archived })
        // Worth and owed are claims about now, and you do not own it now.
        assertEquals(1_500_000L, ledger.worthCents)
        assertEquals(800_000L, ledger.owedCents)
        assertEquals(700_000L, ledger.equityCents)
        assertEquals(1, ledger.assets)
    }

    @Test
    fun `equity is null rather than a minus sign when nothing has been valued`() {
        val ledger = Ledgers.of(
            assets = listOf(asset("truck", worth = null, owed = 800_000)),
            entries = emptyList(),
            coverages = emptyMap(),
            since = yearAgo,
            now = now
        )

        // Otherwise "equity" is the loan balance with a minus on it, which reads as a fact and is
        // an artefact of an empty field.
        assertNull(ledger.equityCents)
        assertEquals(800_000L, ledger.owedCents)
    }

    @Test
    fun `an empty register says so`() {
        val ledger = Ledgers.of(emptyList(), emptyList(), emptyMap(), since = yearAgo, now = now)

        assertTrue(ledger.isEmpty)
        assertTrue(ledger.spend.isEmpty())
        assertTrue(ledger.vendors.isEmpty())
    }

    // ------------------------------------------------------------------ who did the brakes

    @Test
    fun `one garage typed three ways is one garage, under the spelling used last`() {
        val ledger = Ledgers.of(
            assets = listOf(asset("truck"), asset("saab")),
            entries = listOf(
                work("truck", 20_000, daysAgo = 300, vendor = "quick lube"),
                work("truck", 30_000, daysAgo = 100, vendor = "Quick  Lube "),
                work("saab", 10_000, daysAgo = 20, vendor = "Quick Lube")
            ),
            coverages = emptyMap(),
            since = yearAgo,
            now = now
        )

        val vendor = ledger.vendors.single()
        assertEquals("Quick Lube", vendor.name)
        assertEquals(3, vendor.visits)
        assertEquals(60_000L, vendor.totalCents)
        // The reason this is worth crossing assets for.
        assertEquals(2, vendor.assets)
    }

    @Test
    fun `vendors are listed by what they have had, and blanks are not vendors`() {
        val directory = Vendors.directory(
            listOf(
                work("truck", 5_000, daysAgo = 1, vendor = "Cheap Garage"),
                work("truck", 90_000, daysAgo = 2, vendor = "The Dealer"),
                work("truck", 40_000, daysAgo = 3, vendor = "   "),
                work("truck", 40_000, daysAgo = 4, vendor = null)
            )
        )

        assertEquals(listOf("The Dealer", "Cheap Garage"), directory.map { it.name })
    }

    @Test
    fun `suggestions complete what you are typing, most recent first`() {
        val entries = listOf(
            work("truck", 5_000, daysAgo = 90, vendor = "Quick Lube on 5th"),
            work("truck", 5_000, daysAgo = 30, vendor = "Dave's Tyres"),
            work("truck", 5_000, daysAgo = 2, vendor = "The Dealer")
        )

        assertEquals(listOf("The Dealer", "Dave's Tyres", "Quick Lube on 5th"), Vendors.suggestions(entries, ""))
        // Matching on containing rather than starting with: a garage is remembered by the street as
        // often as by its name.
        assertEquals(listOf("Quick Lube on 5th"), Vendors.suggestions(entries, "5th"))
        assertEquals(listOf("Dave's Tyres"), Vendors.suggestions(entries, "dave"))
        // Nothing to complete when it is already typed out.
        assertTrue(Vendors.suggestions(entries, "The Dealer").isEmpty())
        assertTrue(Vendors.suggestions(entries, "nobody").isEmpty())
    }

    @Test
    fun `a name is tidied before it is compared`() {
        assertEquals("Quick Lube", Vendors.normalise("  Quick   Lube  "))
        assertNull(Vendors.normalise("   "))
        assertNull(Vendors.normalise(null))
        assertEquals(Vendors.key("QUICK LUBE"), Vendors.key(" quick  lube "))
    }
}
