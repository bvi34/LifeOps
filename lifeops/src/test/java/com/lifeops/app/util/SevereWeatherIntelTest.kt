package com.lifeops.app.util

import com.lifeops.app.data.model.AlertSeverity
import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.Wind
import org.junit.Assert.*
import org.junit.Test

class SevereWeatherIntelTest {

    private val now = "2026-07-19T12:00:00Z"

    private fun hour(offsetHours: Int, forecast: String) = ForecastPeriod(
        name = "h$offsetHours",
        startTime = "2026-07-19T${(12 + offsetHours).toString().padStart(2, '0')}:00:00Z",
        endTime = "2026-07-19T${(13 + offsetHours).toString().padStart(2, '0')}:00:00Z",
        isDaytime = true, temperatureF = 80, wind = Wind(5, "S"),
        precipitationProbabilityPct = 10, shortForecast = forecast
    )

    @Test
    fun `active severe alert produces an advisory with a delay until expiry`() {
        val alert = WeatherAlert(
            id = "a1", event = "Severe Thunderstorm Warning", severity = AlertSeverity.SEVERE,
            headline = "Damaging winds", description = null, expires = "2026-07-19T13:30:00Z"
        )
        val advisories = SevereWeatherIntel.advise(listOf(alert), emptyList(), now)
        assertEquals(1, advisories.size)
        val a = advisories.first()
        assertEquals("Severe Thunderstorm Warning", a.headline)
        // Expires 90 minutes after now.
        assertEquals(90, a.suggestedDelayMinutes)
        assertNotNull(a.delayHint)
    }

    @Test
    fun `an upcoming forecast storm is flagged as approaching with a clear-by delay`() {
        val hourly = listOf(
            hour(0, "Sunny"),
            hour(2, "Scattered Thunderstorms"),
            hour(4, "Sunny")
        )
        val advisories = SevereWeatherIntel.advise(emptyList(), hourly, now)
        assertEquals(1, advisories.size)
        val a = advisories.first()
        assertEquals("Storm approaching", a.headline)
        // Clears at +4h -> 240 minutes out.
        assertEquals(240, a.suggestedDelayMinutes)
    }

    @Test
    fun `no storms yields no advisories`() {
        val hourly = listOf(hour(0, "Sunny"), hour(1, "Partly Cloudy"), hour(2, "Clear"))
        assertTrue(SevereWeatherIntel.advise(emptyList(), hourly, now).isEmpty())
    }

    @Test
    fun `a distant storm beyond the action window is not advised`() {
        // Storm 9 hours out — past the "approaching" horizon.
        val hourly = listOf(hour(0, "Sunny"), hour(9, "Thunderstorms"))
        assertTrue(SevereWeatherIntel.advise(emptyList(), hourly, now).isEmpty())
    }

    @Test
    fun `delay hint formats hours and minutes`() {
        assertEquals("Delay ~30 min", WeatherAdvisory("h", "d", 30, 0).delayHint)
        assertEquals("Delay ~1h", WeatherAdvisory("h", "d", 60, 0).delayHint)
        assertEquals("Delay ~2h", WeatherAdvisory("h", "d", 150, 0).delayHint)
        assertNull(WeatherAdvisory("h", "d", null, 0).delayHint)
    }
}
