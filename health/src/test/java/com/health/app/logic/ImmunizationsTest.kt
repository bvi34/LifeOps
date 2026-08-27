package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vaccination record. As with the allergy check, the most important assertions here are about
 * what Health refuses to say: there is no "due", no "overdue" and no "up to date" anywhere in the
 * output, because Health ships no schedule and cannot have an opinion about one.
 */
class ImmunizationsTest {

    private fun dose(
        vaccine: String,
        date: String?,
        number: Int? = null,
        source: VaccineSource = VaccineSource.TRANSCRIBED
    ) = VaccineDose("d-$vaccine-$date-$number", vaccine, date, number, source)

    @Test
    fun `doses of one vaccine group into one series, oldest first`() {
        val series = Immunizations.group(
            listOf(
                dose("MMR", "2021-09-14", 2),
                dose("MMR", "2019-03-14", 1)
            )
        )
        assertEquals(1, series.size)
        assertEquals(listOf(1, 2), series[0].doses.map { it.doseNumber })
    }

    @Test
    fun `punctuation does not split a child's record in two`() {
        val series = Immunizations.group(listOf(dose("MMR", "2019-03-14"), dose("M.M.R.", "2021-09-14")))
        assertEquals(1, series.size)
        assertEquals(2, series[0].doses.size)
        // The name shown is the one from the most recent dose — how they are writing it now.
        assertEquals("M.M.R.", series[0].name)
    }

    @Test
    fun `different vaccines stay different series`() {
        val series = Immunizations.group(listOf(dose("MMR", "2019-03-14"), dose("DTaP", "2019-05-14")))
        assertEquals(2, series.size)
    }

    @Test
    fun `the summary reports what is recorded and never that anybody is up to date`() {
        val series = Immunizations.group(
            listOf(dose("MMR", "2019-03-14", 1), dose("MMR", "2021-09", 2))
        ).single()
        assertEquals("2 doses recorded · latest September 2021", series.summary)
        // The sentence this whole file exists to avoid.
        assertFalse(series.summary.contains("up to date", ignoreCase = true))
        assertFalse(series.summary.contains("due", ignoreCase = true))
    }

    @Test
    fun `one dose reads as a dose, not as doses`() {
        val series = Immunizations.group(listOf(dose("BCG", "2018-01-04"))).single()
        assertTrue(series.summary.startsWith("1 dose recorded"))
    }

    @Test
    fun `a transcribed month-only date says only the month`() {
        val series = Immunizations.group(listOf(dose("Influenza", "2025-10"))).single()
        assertEquals("1 dose recorded · latest October 2025", series.summary)
    }

    @Test
    fun `an undated dose is kept, and says its date is not recorded`() {
        val series = Immunizations.group(listOf(dose("Hepatitis B", null))).single()
        assertEquals("1 dose recorded", series.summary)
        assertTrue(series.doses[0].descriptor.contains("Date not recorded"))
    }

    @Test
    fun `an undated dose sorts last within its series rather than first`() {
        val series = Immunizations.group(
            listOf(dose("MMR", null, 3), dose("MMR", "2019-03-14", 1))
        ).single()
        assertEquals(listOf(1, 3), series.doses.map { it.doseNumber })
    }

    @Test
    fun `a series with nothing dated sorts below one with a date`() {
        val series = Immunizations.group(listOf(dose("Undated", null), dose("Dated", "2019-03-14")))
        assertEquals(listOf("Dated", "Undated"), series.map { it.name })
    }

    @Test
    fun `the most recently given series is read first`() {
        val series = Immunizations.group(
            listOf(dose("Old", "2015-01-01"), dose("Recent", "2025-01-01"))
        )
        assertEquals(listOf("Recent", "Old"), series.map { it.name })
    }

    @Test
    fun `a dose from memory is marked as one and a witnessed dose is not accused of anything`() {
        val recalled = Immunizations.group(
            listOf(dose("Tetanus", "2010", source = VaccineSource.RECALLED))
        ).single()
        assertTrue(recalled.hasRecalledDose)
        assertTrue(recalled.doses[0].descriptor.contains("From memory"))

        val witnessed = Immunizations.group(
            listOf(dose("Tetanus", "2010", source = VaccineSource.WITNESSED))
        ).single()
        assertFalse(witnessed.hasRecalledDose)
    }

    @Test
    fun `a source nobody recorded says nothing rather than guessing which it was`() {
        val series = Immunizations.group(
            listOf(dose("MMR", "2019-03-14", 1, VaccineSource.UNKNOWN))
        ).single()
        assertEquals("Dose 1 · 14 March 2019", series.doses[0].descriptor)
    }

    @Test
    fun `a blank vaccine name is dropped rather than becoming an unnamed series`() {
        assertTrue(Immunizations.group(listOf(dose("   ", "2019-03-14"))).isEmpty())
    }

    @Test
    fun `the total counts every recorded dose across every series`() {
        val series = Immunizations.group(
            listOf(dose("MMR", "2019-03-14"), dose("MMR", "2021-09-14"), dose("DTaP", "2019-05-14"))
        )
        assertEquals(3, Immunizations.totalRecorded(series))
    }
}
