package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeSummaryTest {

    private val now = 1_700_000_000_000L
    private fun hoursAgo(h: Double) = now - (h * 60 * 60 * 1000).toLong()

    @Test
    fun `an empty episode summarises without inventing anything`() {
        val summary = EpisodeSummaries.summarize(
            EpisodeFacts(title = "Cold", startedAtMillis = hoursAgo(5.0)),
            now
        )
        assertEquals(0, summary.readingCount)
        assertEquals(TempTrend.UNKNOWN, summary.trend)
        assertEquals(CareLevel.ROUTINE, summary.careLevel)
        assertTrue(summary.headline.contains("no temperature recorded"))
    }

    @Test
    fun `peak and latest are different questions`() {
        val facts = EpisodeFacts(
            title = "Flu",
            startedAtMillis = hoursAgo(30.0),
            temps = listOf(
                TempPoint(hoursAgo(30.0), 38.2),
                TempPoint(hoursAgo(20.0), 39.6),
                TempPoint(hoursAgo(2.0), 37.9)
            )
        )
        val summary = EpisodeSummaries.summarize(facts, now)
        assertEquals(39.6, summary.peak!!.celsius, 0.001)
        assertEquals(37.9, summary.latest!!.celsius, 0.001)
        assertEquals(2, summary.feverReadings)
    }

    @Test
    fun `trend needs more than one reading and ignores thermometer noise`() {
        assertEquals(TempTrend.UNKNOWN, EpisodeSummaries.trendOf(listOf(TempPoint(hoursAgo(1.0), 38.5))))

        val steady = listOf(
            TempPoint(hoursAgo(6.0), 38.4),
            TempPoint(hoursAgo(3.0), 38.5),
            TempPoint(hoursAgo(1.0), 38.5)
        )
        assertEquals(TempTrend.STEADY, EpisodeSummaries.trendOf(steady))

        val rising = listOf(
            TempPoint(hoursAgo(6.0), 37.6),
            TempPoint(hoursAgo(3.0), 38.0),
            TempPoint(hoursAgo(1.0), 39.1)
        )
        assertEquals(TempTrend.RISING, EpisodeSummaries.trendOf(rising))

        val falling = listOf(
            TempPoint(hoursAgo(6.0), 39.2),
            TempPoint(hoursAgo(3.0), 38.9),
            TempPoint(hoursAgo(1.0), 37.4)
        )
        assertEquals(TempTrend.FALLING, EpisodeSummaries.trendOf(falling))
    }

    @Test
    fun `the fever run is measured from the start of the current run only`() {
        // Feverish, then a clear reading, then feverish again: the run is the second stretch.
        val temps = listOf(
            TempPoint(hoursAgo(50.0), 38.6),
            TempPoint(hoursAgo(40.0), 37.0),
            TempPoint(hoursAgo(10.0), 38.4),
            TempPoint(hoursAgo(2.0), 38.9)
        )
        assertEquals(10, EpisodeSummaries.feverRunHours(temps, now))
    }

    @Test
    fun `a run that has broken reports no current fever`() {
        val temps = listOf(TempPoint(hoursAgo(20.0), 39.0), TempPoint(hoursAgo(1.0), 36.9))
        assertEquals(0, EpisodeSummaries.feverRunHours(temps, now))
    }

    @Test
    fun `a fever into its fourth day escalates to calling someone`() {
        val temps = (0..8).map { i -> TempPoint(hoursAgo(80.0 - i * 10.0), 38.6) }
        val summary = EpisodeSummaries.summarize(
            EpisodeFacts(title = "Flu", startedAtMillis = hoursAgo(84.0), temps = temps),
            now
        )
        assertTrue(summary.feverRunHours >= 72)
        assertEquals(CareLevel.CALL_DOCTOR, summary.careLevel)
        assertTrue(summary.advice.any { it.contains("day(s)") })
    }

    @Test
    fun `a baby's episode inherits the age-aware care level`() {
        val summary = EpisodeSummaries.summarize(
            EpisodeFacts(
                title = "Temperature",
                startedAtMillis = hoursAgo(3.0),
                ageMonths = 2,
                temps = listOf(TempPoint(hoursAgo(0.5), 38.1, TempSite.RECTAL))
            ),
            now
        )
        assertEquals(CareLevel.SEEK_CARE_NOW, summary.careLevel)
    }

    @Test
    fun `symptoms split into what is still going and what has passed`() {
        val facts = EpisodeFacts(
            title = "Cold",
            startedAtMillis = hoursAgo(40.0),
            symptoms = listOf(
                SymptomPoint("Cough", 3, hoursAgo(40.0)),
                SymptomPoint("Sore throat", 4, hoursAgo(38.0), endedAtMillis = hoursAgo(6.0)),
                SymptomPoint("Headache", 5, hoursAgo(12.0))
            ),
            doses = listOf(DosePoint("Paracetamol", hoursAgo(4.0), 500.0, "mg"))
        )
        val summary = EpisodeSummaries.summarize(facts, now)
        assertEquals(listOf("Headache", "Cough"), summary.activeSymptoms.map { it.name })
        assertEquals(listOf("Sore throat"), summary.resolvedSymptoms.map { it.name })
        assertEquals(1, summary.doseCount)
        assertEquals("Paracetamol", summary.lastDose!!.name)
    }

    @Test
    fun `a stale episode asks for a fresh reading`() {
        val summary = EpisodeSummaries.summarize(
            EpisodeFacts(
                title = "Flu",
                startedAtMillis = hoursAgo(40.0),
                temps = listOf(TempPoint(hoursAgo(20.0), 37.2))
            ),
            now
        )
        assertTrue(summary.advice.any { it.contains("No reading in over 12 hours") })
    }

    @Test
    fun `an ended episode is reported in the past tense and stops accruing`() {
        val summary = EpisodeSummaries.summarize(
            EpisodeFacts(
                title = "Flu",
                startedAtMillis = hoursAgo(100.0),
                endedAtMillis = hoursAgo(28.0),
                temps = listOf(TempPoint(hoursAgo(90.0), 38.8), TempPoint(hoursAgo(30.0), 36.9))
            ),
            now
        )
        assertEquals(72, summary.durationHours)
        assertTrue(summary.headline.contains("ended after"))
        assertTrue(summary.advice.none { it.contains("No reading in over 12 hours") })
    }
}
