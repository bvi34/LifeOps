package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightTest {

    @Test
    fun `converts both ways around the known anchors`() {
        assertEquals(45.359, Weight.toKilograms(100.0, WeightUnit.POUNDS), 0.001)
        assertEquals(220.462, Weight.fromKilograms(100.0, WeightUnit.POUNDS), 0.001)
        assertEquals(70.5, Weight.toKilograms(70.5, WeightUnit.KILOGRAMS), 0.0)
    }

    @Test
    fun `a round trip through pounds comes back to the same weight`() {
        // What a household switching units back and forth is entitled to: the stored kilograms are
        // the only truth, and reading them out in pounds and back must not drift.
        val kilograms = 8.4
        val pounds = Weight.fromKilograms(kilograms, WeightUnit.POUNDS)
        assertEquals(kilograms, Weight.toKilograms(pounds, WeightUnit.POUNDS), 1e-9)
    }

    @Test
    fun `a difference converts as a difference`() {
        assertEquals(2.2046, Weight.deltaFromKilograms(1.0, WeightUnit.POUNDS), 0.001)
        assertEquals(-1.0, Weight.deltaFromKilograms(-1.0, WeightUnit.KILOGRAMS), 0.001)
    }

    @Test
    fun `parses what people actually type`() {
        assertEquals(70.5, Weight.parseToKilograms("70.5", WeightUnit.KILOGRAMS)!!, 0.001)
        assertEquals(70.5, Weight.parseToKilograms(" 70,5 kg ", WeightUnit.KILOGRAMS)!!, 0.001)
        assertEquals(45.359, Weight.parseToKilograms("100 lbs", WeightUnit.POUNDS)!!, 0.001)
        assertEquals(45.359, Weight.parseToKilograms("100lb", WeightUnit.POUNDS)!!, 0.001)
    }

    @Test
    fun `rejects weights no person has`() {
        assertNull(Weight.parseToKilograms("705", WeightUnit.KILOGRAMS))
        assertNull(Weight.parseToKilograms("0", WeightUnit.KILOGRAMS))
        assertNull(Weight.parseToKilograms("2000", WeightUnit.POUNDS))
        assertNull(Weight.parseToKilograms("", WeightUnit.KILOGRAMS))
        assertNull(Weight.parseToKilograms("heavy", WeightUnit.KILOGRAMS))
    }

    @Test
    fun `a newborn is still a believable weight`() {
        assertEquals(3.2, Weight.parseToKilograms("3.2", WeightUnit.KILOGRAMS)!!, 0.001)
        assertEquals(3.401, Weight.parseToKilograms("7.5", WeightUnit.POUNDS)!!, 0.001)
    }

    @Test
    fun `formats to one decimal in the chosen unit`() {
        assertEquals("70.5 kg", Weight.format(70.54, WeightUnit.KILOGRAMS))
        assertEquals("155.4 lb", Weight.format(70.5, WeightUnit.POUNDS))
        assertEquals("70.5", Weight.formatBare(70.5, WeightUnit.KILOGRAMS))
    }

    @Test
    fun `a change carries its sign`() {
        assertEquals("+1.5 kg", Weight.formatDelta(1.5, WeightUnit.KILOGRAMS))
        assertEquals("-2.2 lb", Weight.formatDelta(-1.0, WeightUnit.POUNDS))
        assertEquals("0.0 kg", Weight.formatDelta(-0.0, WeightUnit.KILOGRAMS))
    }
}
