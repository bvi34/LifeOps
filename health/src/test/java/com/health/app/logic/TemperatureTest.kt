package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemperatureTest {

    @Test
    fun `converts both ways around the known anchors`() {
        assertEquals(37.0, Temperature.toCelsius(98.6, TempUnit.FAHRENHEIT), 0.05)
        assertEquals(98.6, Temperature.fromCelsius(37.0, TempUnit.FAHRENHEIT), 0.05)
        assertEquals(38.0, Temperature.toCelsius(38.0, TempUnit.CELSIUS), 0.0)
    }

    @Test
    fun `a difference converts as a difference, not as a point`() {
        // The classic bug: 0.5 degrees of change reported as 32.9.
        assertEquals(0.9, Temperature.deltaFromCelsius(0.5, TempUnit.FAHRENHEIT), 0.001)
        assertEquals(0.5, Temperature.deltaFromCelsius(0.5, TempUnit.CELSIUS), 0.001)
    }

    @Test
    fun `parses what people actually type`() {
        assertEquals(38.4, Temperature.parseToCelsius("38.4", TempUnit.CELSIUS)!!, 0.001)
        assertEquals(38.4, Temperature.parseToCelsius(" 38,4 °C ", TempUnit.CELSIUS)!!, 0.001)
        assertEquals(37.0, Temperature.parseToCelsius("98.6F", TempUnit.FAHRENHEIT)!!, 0.05)
    }

    @Test
    fun `rejects values no body has`() {
        assertNull(Temperature.parseToCelsius("986", TempUnit.FAHRENHEIT))
        assertNull(Temperature.parseToCelsius("380", TempUnit.CELSIUS))
        assertNull(Temperature.parseToCelsius("", TempUnit.CELSIUS))
        assertNull(Temperature.parseToCelsius("warm", TempUnit.CELSIUS))
    }

    @Test
    fun `formats to one decimal in the chosen unit`() {
        assertEquals("38.4 °C", Temperature.format(38.44, TempUnit.CELSIUS))
        assertEquals("101.1 °F", Temperature.format(38.4, TempUnit.FAHRENHEIT))
        assertEquals("+0.9 °F", Temperature.formatDelta(0.5, TempUnit.FAHRENHEIT))
        assertEquals("-0.3 °C", Temperature.formatDelta(-0.3, TempUnit.CELSIUS))
    }
}
