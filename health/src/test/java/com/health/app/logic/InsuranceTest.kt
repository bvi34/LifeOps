package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The two things a copied insurance card can honestly support: whether the coverage is current, and
 * what belongs on each face of it. Every case here is one a wallet actually contains — a card with
 * no end date printed on it, a child on a parent's policy, a plan that lapsed in March.
 */
class InsuranceTest {

    private val today = LocalDate.of(2026, 3, 15)

    @Test
    fun `a card with no dates says so rather than assuming it is current`() {
        val assessment = Insurance.assessCoverage(null, null, today)
        assertEquals(CoverageStatus.UNKNOWN, assessment.status)
        assertNull(assessment.daysToEnd)
        // The common case: most cards print an effective date and no end date at all, and a plan
        // with no printed end is the one Health has no business declaring active or lapsed.
        assertTrue(assessment.summary.contains("No effective date"))
    }

    @Test
    fun `an effective date in the past with no end date is simply active`() {
        val assessment = Insurance.assessCoverage("2026-01-01", null, today)
        assertEquals(CoverageStatus.ACTIVE, assessment.status)
        assertNull(assessment.daysToEnd)
        assertTrue(assessment.summary.contains("1 Jan 2026"))
    }

    @Test
    fun `a plan that starts next month has not started`() {
        val assessment = Insurance.assessCoverage("2026-04-01", null, today)
        assertEquals(CoverageStatus.NOT_STARTED, assessment.status)
        assertTrue(assessment.status.needsAttention)
    }

    @Test
    fun `an end date in the past is ended, and knows by how long`() {
        val assessment = Insurance.assessCoverage("2025-01-01", "2026-02-28", today)
        assertEquals(CoverageStatus.ENDED, assessment.status)
        assertEquals(-15L, assessment.daysToEnd)
        assertTrue(assessment.summary.startsWith("Ended"))
    }

    @Test
    fun `the end date itself still counts as covered`() {
        val assessment = Insurance.assessCoverage("2025-01-01", "2026-03-15", today)
        assertEquals(CoverageStatus.ENDING_SOON, assessment.status)
        assertEquals(0L, assessment.daysToEnd)
        assertTrue(assessment.summary.contains("Ends today"))
    }

    @Test
    fun `ending soon reaches a month ahead and no further`() {
        assertEquals(
            CoverageStatus.ENDING_SOON,
            Insurance.assessCoverage("2025-01-01", "2026-04-15", today).status
        )
        assertEquals(
            CoverageStatus.ACTIVE,
            Insurance.assessCoverage("2025-01-01", "2026-04-16", today).status
        )
    }

    @Test
    fun `a plan that ended is reported as ended even though it also has not started`() {
        // A renewal typed in back-to-front: both dates in the past, end before today. Ended wins,
        // because "this card is no good" is the fact that matters at the desk.
        val assessment = Insurance.assessCoverage("2026-05-01", "2026-01-31", today)
        assertEquals(CoverageStatus.ENDED, assessment.status)
    }

    @Test
    fun `an unparseable date leaves the coverage undated rather than lapsed`() {
        assertNull(Insurance.parseDate("last January"))
        assertEquals(CoverageStatus.UNKNOWN, Insurance.assessCoverage("open enrollment", "", today).status)
    }

    @Test
    fun `fields nobody filled in never reach the card`() {
        val layout = Insurance.card(
            CardFacts(carrierName = "Meridian Mutual", memberId = "W901234567")
        )
        assertEquals("Meridian Mutual", layout.front.title)
        assertEquals(listOf("Member ID"), layout.front.fields.map { it.label })
        // A line reading "Group —" invites the reader to conclude the plan has no group number.
        assertTrue(layout.front.fields.none { it.value.isBlank() })
    }

    @Test
    fun `the front carries who you are and the back carries who to ring`() {
        val layout = Insurance.card(
            CardFacts(
                carrierName = "Meridian Mutual",
                planName = "Choice Plus",
                planType = PlanType.PPO,
                memberName = "Ada Okonkwo",
                memberId = "W901234567",
                groupNumber = "GRP-4471",
                effectiveDate = "2026-01-01",
                payerId = "87726",
                rxBin = "610014",
                memberServicesPhone = "1-800-555-0142",
                directoryUrl = "https://api.meridian.example/fhir"
            )
        )
        assertEquals("Choice Plus", layout.front.subtitle)
        assertEquals(
            listOf("Member", "Member ID", "Group", "Plan", "Effective"),
            layout.front.fields.map { it.label }
        )
        assertEquals("1 Jan 2026", layout.front.fields.first { it.label == "Effective" }.value)
        assertEquals(
            listOf("Payer ID", "Rx BIN", "Member services", "Provider directory"),
            layout.back.fields.map { it.label }
        )
    }

    @Test
    fun `the subscriber is named only when it is somebody else`() {
        val self = Insurance.card(
            CardFacts(carrierName = "X", memberName = "Ada Okonkwo", subscriberName = "ada okonkwo")
        )
        assertTrue(self.front.fields.none { it.label == "Subscriber" })

        val child = Insurance.card(
            CardFacts(
                carrierName = "X",
                memberName = "Chidi Okonkwo",
                subscriberName = "Ada Okonkwo",
                relationshipToSubscriber = "Child"
            )
        )
        assertEquals("Ada Okonkwo", child.front.fields.first { it.label == "Subscriber" }.value)
        assertEquals("Child", child.front.fields.first { it.label == "Relationship" }.value)
    }

    @Test
    fun `a dental card says so and a medical one does not need to`() {
        val dental = Insurance.card(
            CardFacts(carrierName = "X", planName = "Smile", coverageKind = CoverageKind.DENTAL)
        )
        assertEquals("Smile · Dental", dental.front.subtitle)
        val medical = Insurance.card(CardFacts(carrierName = "X", planName = "Smile"))
        assertEquals("Smile", medical.front.subtitle)
    }

    @Test
    fun `every exported face carries the line saying what this is not`() {
        val layout = Insurance.card(CardFacts(carrierName = "Meridian Mutual"))
        assertTrue(layout.front.footnotes.contains(Insurance.CARD_DISCLAIMER))
        assertTrue(layout.back.footnotes.contains(Insurance.CARD_DISCLAIMER))
        assertTrue(Insurance.CARD_DISCLAIMER.contains("Not issued by the insurer"))
    }

    @Test
    fun `a plan note goes on the back above the disclaimer`() {
        val layout = Insurance.card(CardFacts(carrierName = "X", note = "Referral needed for specialists"))
        assertEquals("Referral needed for specialists", layout.back.footnotes.first())
        assertEquals(Insurance.CARD_DISCLAIMER, layout.back.footnotes.last())
    }

    @Test
    fun `a browsing list shows the last four digits and nothing more`() {
        assertEquals("••• 4567", Insurance.maskMemberId("W901234567"))
        // Short enough that masking would hide the whole thing and tell nobody anything.
        assertEquals("1234", Insurance.maskMemberId("1234"))
        assertNull(Insurance.maskMemberId("   "))
        assertFalse(Insurance.maskMemberId("W901234567")!!.contains("901"))
    }
}
