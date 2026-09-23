package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The line and date a medical record from Health Connect is listed under. */
class FhirSummaryTest {

    @Test
    fun `an immunization is named by its vaccine and dated when it was given`() {
        val summary = FhirSummaries.of(
            """{"resourceType":"Immunization","vaccineCode":{"coding":[{"display":"MMR"}]},
               "occurrenceDateTime":"2021-04-12T10:30:00Z"}"""
        )
        assertEquals("Immunization", summary.resourceType)
        assertEquals("MMR", summary.title)
        assertEquals(FhirSummaries.parseDate("2021-04-12T10:30:00Z"), summary.date)
    }

    @Test
    fun `text beats a coding's display`() {
        val summary = FhirSummaries.of(
            """{"resourceType":"Condition","code":{"text":"Asthma","coding":[{"display":"J45"}]},
               "onsetDateTime":"2019"}"""
        )
        assertEquals("Asthma", summary.title)
        assertEquals(FhirSummaries.parseDate("2019-01-01"), summary.date)
    }

    @Test
    fun `an encounter falls back to its period`() {
        val summary = FhirSummaries.of(
            """{"resourceType":"Encounter","type":[{"text":"Check-up"}],"period":{"start":"2024-02-03"}}"""
        )
        assertEquals("Check-up", summary.title)
        assertEquals(FhirSummaries.parseDate("2024-02-03"), summary.date)
    }

    @Test
    fun `a patient is named by their name`() {
        val summary = FhirSummaries.of(
            """{"resourceType":"Patient","name":[{"given":["Ada","May"],"family":"Lovelace"}],"birthDate":"2019-03-14"}"""
        )
        assertEquals("Ada May Lovelace", summary.title)
    }

    @Test
    fun `partial dates are the start of what they name`() {
        assertEquals(FhirSummaries.parseDate("2024-03-01"), FhirSummaries.parseDate("2024-03"))
        assertEquals(FhirSummaries.parseDate("2024-01-01"), FhirSummaries.parseDate("2024"))
        assertEquals(
            FhirSummaries.parseDate("2024-03-14T00:00:00Z"),
            FhirSummaries.parseDate("2024-03-14T02:00:00+02:00")
        )
        assertNull(FhirSummaries.parseDate("last spring"))
    }

    @Test
    fun `something that isn't JSON is summarised as nothing, not a crash`() {
        val summary = FhirSummaries.of("not json {")
        assertNull(summary.title)
        assertNull(summary.date)
    }
}
