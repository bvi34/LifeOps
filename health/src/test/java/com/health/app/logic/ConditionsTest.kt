package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Long-running conditions, and the onset dates people actually remember. The recurring theme is that
 * a partial date is read as the partial date it is, rather than padded out into a precision nobody
 * gave.
 */
class ConditionsTest {

    private val today = LocalDate.of(2026, 3, 15)

    @Test
    fun `a bare year is read as a year and says only that`() {
        val onset = Conditions.parseOnset("2019")
        assertEquals(LocalDate.of(2019, 1, 1), onset?.date)
        assertEquals(DatePrecision.YEAR, onset?.precision)
        // Not "Since 1 January 2019" — nobody said the first of January.
        assertEquals("Since 2019 · 7 years", Conditions.describeOnset("2019", today))
    }

    @Test
    fun `a month is read as a month`() {
        val onset = Conditions.parseOnset("2019-03")
        assertEquals(LocalDate.of(2019, 3, 1), onset?.date)
        assertEquals(DatePrecision.MONTH, onset?.precision)
        assertEquals("Since March 2019 · 7 years", Conditions.describeOnset("2019-03", today))
    }

    @Test
    fun `a full date is read in full`() {
        assertEquals(DatePrecision.DAY, Conditions.parseOnset("2019-03-14")?.precision)
        assertEquals("Since 14 March 2019 · 7 years", Conditions.describeOnset("2019-03-14", today))
    }

    @Test
    fun `an onset rounds to the start of its period, where an expiry rounds to the end of one`() {
        // The two partial-date rules point opposite ways on purpose: both round in the direction
        // that cannot overstate what was actually written down.
        assertEquals(LocalDate.of(2026, 3, 1), Conditions.parseOnset("2026-03")?.date)
        assertEquals(LocalDate.of(2026, 3, 31), Cabinet.parseExpiry("2026-03"))
    }

    @Test
    fun `an unparseable onset is no date rather than a guessed one`() {
        assertNull(Conditions.parseOnset("when she was little"))
        assertNull(Conditions.parseOnset("2019-13"))
        assertNull(Conditions.parseOnset(null))
        assertNull(Conditions.describeOnset("sometime in the nineties", today))
    }

    @Test
    fun `a future onset is described as nothing rather than as negative time`() {
        assertNull(Conditions.describeOnset("2030", today))
        assertNull(Conditions.describeElapsed(LocalDate.of(2030, 1, 1), today))
    }

    @Test
    fun `elapsed time uses the unit a person would say out loud`() {
        assertNull(Conditions.describeElapsed(LocalDate.of(2026, 3, 1), today))
        assertEquals("2 months", Conditions.describeElapsed(LocalDate.of(2026, 1, 10), today))
        assertEquals("1 month", Conditions.describeElapsed(LocalDate.of(2026, 2, 10), today))
        assertEquals("1 year", Conditions.describeElapsed(LocalDate.of(2025, 3, 1), today))
        assertEquals("7 years", Conditions.describeElapsed(LocalDate.of(2019, 1, 1), today))
    }

    @Test
    fun `what is still going is read before what is over, longest-standing first`() {
        data class Row(val name: String, val status: ConditionStatus, val onset: String?)

        val sorted = Conditions.sort(
            listOf(
                Row("Eczema", ConditionStatus.RESOLVED, "2015"),
                Row("Migraine", ConditionStatus.ACTIVE, "2022"),
                Row("Asthma", ConditionStatus.ACTIVE, "2019"),
                Row("Anaemia", ConditionStatus.REMISSION, "2021")
            ),
            status = { it.status },
            onset = { it.onset },
            name = { it.name }
        )
        assertEquals(listOf("Asthma", "Migraine", "Anaemia", "Eczema"), sorted.map { it.name })
    }

    @Test
    fun `a condition with no onset date sorts last rather than first`() {
        data class Row(val name: String, val status: ConditionStatus, val onset: String?)

        val sorted = Conditions.sort(
            listOf(
                Row("Undated", ConditionStatus.ACTIVE, null),
                Row("Dated", ConditionStatus.ACTIVE, "2019")
            ),
            status = { it.status },
            onset = { it.onset },
            name = { it.name }
        )
        assertEquals(listOf("Dated", "Undated"), sorted.map { it.name })
    }

    @Test
    fun `remission is a status of its own, not a kind of resolved`() {
        assertEquals(true, ConditionStatus.REMISSION.isCurrent)
        assertEquals(false, ConditionStatus.RESOLVED.isCurrent)
    }
}
