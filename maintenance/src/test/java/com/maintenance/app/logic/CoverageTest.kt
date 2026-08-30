package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun coverage(
        kind: CoverageKind = CoverageKind.INSURANCE,
        expiresAt: Long? = null,
        premiumCents: Long = 0L,
        period: PremiumPeriod = PremiumPeriod.ANNUAL
    ) = Coverage(
        id = "c1",
        assetId = "a1",
        kind = kind,
        provider = "Some Mutual",
        expiresAt = expiresAt,
        premiumCents = premiumCents,
        period = period
    )

    @Test
    fun `renewals get a month of warning, not a fortnight`() {
        assertEquals(DueStatus.SCHEDULED, Coverages.status(coverage(expiresAt = now + 60 * day), now))
        assertEquals(DueStatus.DUE_SOON, Coverages.status(coverage(expiresAt = now + 20 * day), now))
        assertEquals(DueStatus.OVERDUE, Coverages.status(coverage(expiresAt = now - day), now))
    }

    @Test
    fun `a coverage with no end date is on file, not on the docket`() {
        assertEquals(DueStatus.DORMANT, Coverages.status(coverage(expiresAt = null), now))
        assertEquals("No end date", Coverages.summary(coverage(expiresAt = null), now))
    }

    @Test
    fun `a policy lapses and a warranty expires`() {
        assertEquals("Renews in 10 days", Coverages.summary(coverage(expiresAt = now + 10 * day), now))
        assertEquals("Lapsed 3 days ago", Coverages.summary(coverage(expiresAt = now - 3 * day), now))
        assertEquals(
            "Expires in 3 weeks",
            Coverages.summary(coverage(kind = CoverageKind.WARRANTY, expiresAt = now + 21 * day), now)
        )
    }

    @Test
    fun `premiums annualise, and a one-off premium does not recur`() {
        assertEquals(1_800_00L, Coverages.annualCents(coverage(premiumCents = 150_00L, period = PremiumPeriod.MONTHLY)))
        assertEquals(1_200_00L, Coverages.annualCents(coverage(premiumCents = 600_00L, period = PremiumPeriod.SEMIANNUAL)))
        assertEquals(0L, Coverages.annualCents(coverage(premiumCents = 400_00L, period = PremiumPeriod.ONE_TIME)))
    }

    @Test
    fun `the standing cost of an asset is every coverage on it`() {
        val total = Coverages.annualCents(
            listOf(
                coverage(premiumCents = 150_00L, period = PremiumPeriod.MONTHLY),
                coverage(kind = CoverageKind.REGISTRATION, premiumCents = 92_00L, period = PremiumPeriod.ANNUAL)
            )
        )

        assertEquals(1_892_00L, total)
    }
}
