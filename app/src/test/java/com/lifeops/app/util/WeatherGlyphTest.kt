package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class WeatherGlyphTest {

    @Test
    fun `storm words win over rain and clouds`() {
        assertEquals("⛈️", WeatherGlyph.forShortForecast("Sunny then Thunderstorms"))
        assertEquals("⛈️", WeatherGlyph.forShortForecast("Chance of storms"))
    }

    @Test
    fun `rain snow fog map to their glyphs`() {
        assertEquals("🌧️", WeatherGlyph.forShortForecast("Chance Showers"))
        assertEquals("🌧️", WeatherGlyph.forShortForecast("Light Drizzle"))
        assertEquals("🌨️", WeatherGlyph.forShortForecast("Snow Flurries"))
        assertEquals("🌫️", WeatherGlyph.forShortForecast("Patchy Fog"))
    }

    @Test
    fun `sun and cloud phrasing`() {
        assertEquals("☀️", WeatherGlyph.forShortForecast("Sunny"))
        assertEquals("☀️", WeatherGlyph.forShortForecast("Clear"))
        assertEquals("⛅", WeatherGlyph.forShortForecast("Partly Cloudy"))
        assertEquals("☁️", WeatherGlyph.forShortForecast("Mostly Cloudy"))
        assertEquals("☁️", WeatherGlyph.forShortForecast("Overcast"))
    }

    @Test
    fun `null or unknown falls back to thermometer`() {
        assertEquals("🌡️", WeatherGlyph.forShortForecast(null))
        assertEquals("🌡️", WeatherGlyph.forShortForecast("Blustery"))
    }
}
