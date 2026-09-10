package com.health.app.data.repository

import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.model.ReadingType
import com.health.app.logic.TempSite
import com.health.app.logic.TempUnit
import com.health.app.logic.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A temperature — and a weight — in the history reads in the household's unit.
 *
 * Readings are stored in Celsius and kilograms and converted on the way out, which is the right
 * design and exactly why this needs a test: every screen that shows one has to remember to ask for
 * the conversion, and the history was the one that didn't — it quoted °C to a household that had set
 * the app to °F, in the one place a number gets read out to a doctor.
 */
class ReadingTimelineEntryTest {

    private fun reading(
        celsius: Double,
        site: TempSite = TempSite.ORAL
    ) = entity(ReadingType.TEMPERATURE, celsius, site.key)

    private fun weight(kilograms: Double) = entity(ReadingType.WEIGHT, kilograms, site = null)

    private fun entity(type: ReadingType, value: Double, site: String?) = ReadingEntity(
        id = "r1",
        profileId = "p1",
        episodeId = "e1",
        type = type.key,
        value = value,
        secondaryValue = null,
        site = site,
        takenAt = 1_700_000_000_000L,
        note = null,
        createdAt = 1_700_000_000_000L
    )

    @Test
    fun `a temperature is written in the unit asked for`() {
        assertEquals(
            "38.3 °C (mouth)",
            reading(38.3)
                .toTimelineEntry(ageMonths = 60, unit = TempUnit.CELSIUS, weightUnit = WeightUnit.KILOGRAMS)
                .headline
        )
        assertEquals(
            "100.9 °F (mouth)",
            reading(38.3)
                .toTimelineEntry(ageMonths = 60, unit = TempUnit.FAHRENHEIT, weightUnit = WeightUnit.KILOGRAMS)
                .headline
        )
    }

    @Test
    fun `the site still comes with it, because 37 8 under the arm is not 37 8 in the ear`() {
        val entry = reading(37.8, TempSite.AXILLARY)
            .toTimelineEntry(60, TempUnit.FAHRENHEIT, WeightUnit.KILOGRAMS)
        assertTrue(entry.headline.endsWith("(armpit)"))
    }

    @Test
    fun `the fever verdict is the same number in either unit`() {
        // The assessment is made on the stored Celsius, so changing how it is displayed must not
        // change what Health says about it.
        val celsius = reading(38.3).toTimelineEntry(60, TempUnit.CELSIUS, WeightUnit.KILOGRAMS)
        val fahrenheit = reading(38.3).toTimelineEntry(60, TempUnit.FAHRENHEIT, WeightUnit.KILOGRAMS)
        assertEquals(celsius.careLevel, fahrenheit.careLevel)
        assertEquals(celsius.detail, fahrenheit.detail)
    }

    @Test
    fun `a weight is written in the unit asked for`() {
        assertEquals(
            "70.5 kg",
            weight(70.5).toTimelineEntry(60, TempUnit.CELSIUS, WeightUnit.KILOGRAMS).headline
        )
        assertEquals(
            "155.4 lb",
            weight(70.5).toTimelineEntry(60, TempUnit.CELSIUS, WeightUnit.POUNDS).headline
        )
    }

    @Test
    fun `the weight unit does not leak into the other measurements`() {
        // Pounds are a display choice about weights only: a temperature reads the same either way.
        assertEquals(
            reading(38.3).toTimelineEntry(60, TempUnit.CELSIUS, WeightUnit.KILOGRAMS).headline,
            reading(38.3).toTimelineEntry(60, TempUnit.CELSIUS, WeightUnit.POUNDS).headline
        )
    }
}
