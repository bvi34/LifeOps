package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The cabinet's two verdicts. Every case here is one a household actually hits — a month-only expiry
 * date printed on a box, a bottle measured in mL and dosed in mg, a threshold nobody set.
 */
class CabinetTest {

    private val today = LocalDate.of(2026, 3, 15)

    @Test
    fun `no expiry date is unknown, never a guess that it is fine`() {
        val status = Cabinet.assess(CabinetFacts(quantity = 100.0, quantityUnit = "mL"), today)
        assertEquals(ExpiryStatus.UNKNOWN, status.expiry)
        assertNull(status.daysToExpiry)
    }

    @Test
    fun `a past date is expired`() {
        val status = Cabinet.assess(CabinetFacts(expiryDate = "2026-03-01"), today)
        assertEquals(ExpiryStatus.EXPIRED, status.expiry)
        assertEquals(-14L, status.daysToExpiry)
        assertTrue(status.summary.contains("Expired"))
    }

    @Test
    fun `the printed day itself is still in date`() {
        val status = Cabinet.assess(CabinetFacts(expiryDate = "2026-03-15"), today)
        assertEquals(ExpiryStatus.EXPIRING_SOON, status.expiry)
        assertEquals(0L, status.daysToExpiry)
        assertTrue(status.summary.contains("Expires today"))
    }

    @Test
    fun `a month-only date runs to the end of that month`() {
        // "03/2026" on a box means the product is good through 31 March, not through 1 March.
        val expiry = Cabinet.parseExpiry("2026-03")
        assertEquals(LocalDate.of(2026, 3, 31), expiry)
        assertEquals(ExpiryStatus.EXPIRING_SOON, Cabinet.assess(CabinetFacts(expiryDate = "2026-03"), today).expiry)
    }

    @Test
    fun `expiring soon reaches sixty days and no further`() {
        val soon = Cabinet.assess(CabinetFacts(expiryDate = "2026-05-14"), today)
        val later = Cabinet.assess(CabinetFacts(expiryDate = "2026-05-15"), today)
        assertEquals(ExpiryStatus.EXPIRING_SOON, soon.expiry)
        assertEquals(ExpiryStatus.IN_DATE, later.expiry)
    }

    @Test
    fun `an unparseable date leaves the item undated rather than expired`() {
        assertNull(Cabinet.parseExpiry("next spring"))
        assertEquals(
            ExpiryStatus.UNKNOWN,
            Cabinet.assess(CabinetFacts(expiryDate = "next spring"), today).expiry
        )
    }

    @Test
    fun `doses remaining divides stock by dose when the units match`() {
        val facts = CabinetFacts(
            quantity = 120.0,
            quantityUnit = "mL",
            doseAmount = 15.0,
            doseUnit = "mL"
        )
        assertEquals(8, Cabinet.dosesRemaining(facts))
    }

    @Test
    fun `a dose in a different unit is never converted`() {
        // 120 mL of suspension dosed in mg depends on the concentration. Health says nothing rather
        // than inventing a factor — this is the failure the whole app exists to prevent.
        val facts = CabinetFacts(
            quantity = 120.0,
            quantityUnit = "mL",
            doseAmount = 160.0,
            doseUnit = "mg"
        )
        assertNull(Cabinet.dosesRemaining(facts))
        assertEquals(StockStatus.IN_STOCK, Cabinet.assess(facts, today).stock)
    }

    @Test
    fun `units match case-insensitively and across the plural`() {
        assertTrue(Cabinet.sameUnit("mL", "ml"))
        assertTrue(Cabinet.sameUnit("tablet", "tablets"))
        assertTrue(Cabinet.sameUnit(" Tablets ", "tablet"))
        assertTrue(!Cabinet.sameUnit("mL", "mg"))
        assertTrue(!Cabinet.sameUnit("", ""))
    }

    @Test
    fun `zero stock is out, not low`() {
        val status = Cabinet.assess(CabinetFacts(quantity = 0.0, quantityUnit = "mL"), today)
        assertEquals(StockStatus.OUT, status.stock)
        assertTrue(status.summary.contains("None left"))
    }

    @Test
    fun `a threshold that was set is what low means`() {
        val facts = CabinetFacts(quantity = 20.0, quantityUnit = "mL", lowStockThreshold = 25.0)
        assertEquals(StockStatus.LOW, Cabinet.assess(facts, today).stock)
        assertEquals(
            StockStatus.IN_STOCK,
            Cabinet.assess(facts.copy(quantity = 26.0), today).stock
        )
    }

    @Test
    fun `with no threshold, low means it cannot cover one more dose`() {
        val facts = CabinetFacts(
            quantity = 10.0,
            quantityUnit = "mL",
            doseAmount = 15.0,
            doseUnit = "mL"
        )
        assertEquals(StockStatus.LOW, Cabinet.assess(facts, today).stock)
        assertEquals(0, Cabinet.dosesRemaining(facts))

        // One dose left is not low — it is exactly enough for the next one.
        assertEquals(
            StockStatus.IN_STOCK,
            Cabinet.assess(facts.copy(quantity = 15.0), today).stock
        )
    }

    @Test
    fun `an untracked quantity is unknown rather than empty`() {
        val status = Cabinet.assess(CabinetFacts(quantityUnit = "mL"), today)
        assertEquals(StockStatus.UNKNOWN, status.stock)
        assertTrue(!status.needsAttention)
    }

    @Test
    fun `expired sorts above out of stock, which sorts above expiring soon`() {
        val expired = Cabinet.assess(CabinetFacts(expiryDate = "2020-01-01"), today)
        val out = Cabinet.assess(CabinetFacts(quantity = 0.0, quantityUnit = "mL"), today)
        val soon = Cabinet.assess(CabinetFacts(expiryDate = "2026-04-01"), today)
        val fine = Cabinet.assess(CabinetFacts(quantity = 100.0, quantityUnit = "mL"), today)

        assertTrue(expired.sortRank < out.sortRank)
        assertTrue(out.sortRank < soon.sortRank)
        assertTrue(soon.sortRank < fine.sortRank)
        assertTrue(!fine.needsAttention)
    }

    @Test
    fun `a month-only expiry reads as a month, not as a serial number`() {
        assertEquals("March 2027", Cabinet.describeExpiry("2027-03"))
        assertEquals("2027-03-04", Cabinet.describeExpiry("2027-03-04"))
        assertNull(Cabinet.describeExpiry(null))
    }
}
