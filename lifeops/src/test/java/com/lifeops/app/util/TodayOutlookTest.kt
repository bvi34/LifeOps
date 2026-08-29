package com.lifeops.app.util

import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.Wind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayOutlookTest {

    private fun period(
        name: String,
        isDaytime: Boolean,
        temperatureF: Int,
        precipitationProbabilityPct: Int? = null,
        shortForecast: String = "Sunny"
    ) = ForecastPeriod(
        name = name,
        startTime = "2026-08-29T06:00:00-05:00",
        endTime = "2026-08-29T18:00:00-05:00",
        isDaytime = isDaytime,
        temperatureF = temperatureF,
        wind = Wind(speedMph = 8, directionCardinal = "S"),
        precipitationProbabilityPct = precipitationProbabilityPct,
        shortForecast = shortForecast
    )

    @Test
    fun `morning reads the day as high then low`() {
        val outlook = TodayOutlook.from(
            listOf(
                period("Today", isDaytime = true, temperatureF = 88),
                period("Tonight", isDaytime = false, temperatureF = 67),
                period("Saturday", isDaytime = true, temperatureF = 91)
            )
        )!!
        assertEquals(88, outlook.highF)
        assertEquals(67, outlook.lowF)
        assertEquals("Today", outlook.periodName)
    }

    @Test
    fun `evening still labels tonight the low and tomorrow the high`() {
        // After sundown NWS leads with the night half; nothing may be read off the clock.
        val outlook = TodayOutlook.from(
            listOf(
                period("Tonight", isDaytime = false, temperatureF = 61),
                period("Saturday", isDaytime = true, temperatureF = 84)
            )
        )!!
        assertEquals(84, outlook.highF)
        assertEquals(61, outlook.lowF)
        assertEquals("Tonight", outlook.periodName)
    }

    @Test
    fun `the headline is the leading period's forecast`() {
        val outlook = TodayOutlook.from(
            listOf(
                period("This Afternoon", isDaytime = true, temperatureF = 79, shortForecast = "Chance Showers"),
                period("Tonight", isDaytime = false, temperatureF = 60, shortForecast = "Clear")
            )
        )!!
        assertEquals("Chance Showers", outlook.headline)
    }

    @Test
    fun `precipitation takes the worst of the window, not just the leading period`() {
        // Dry now, wet this evening: the widget must warn about the evening.
        val outlook = TodayOutlook.from(
            listOf(
                period("This Afternoon", isDaytime = true, temperatureF = 79, precipitationProbabilityPct = 10),
                period("Tonight", isDaytime = false, temperatureF = 60, precipitationProbabilityPct = 70)
            )
        )!!
        assertEquals(70, outlook.precipitationProbabilityPct)
    }

    @Test
    fun `periods beyond the window cannot contribute`() {
        // Saturday's heat is not part of "the rest of today".
        val outlook = TodayOutlook.from(
            listOf(
                period("Today", isDaytime = true, temperatureF = 70, precipitationProbabilityPct = 5),
                period("Tonight", isDaytime = false, temperatureF = 55, precipitationProbabilityPct = 5),
                period("Saturday", isDaytime = true, temperatureF = 99, precipitationProbabilityPct = 90)
            )
        )!!
        assertEquals(70, outlook.highF)
        assertEquals(5, outlook.precipitationProbabilityPct)
    }

    @Test
    fun `a missing half of the day is reported as missing rather than guessed`() {
        val outlook = TodayOutlook.from(listOf(period("Tonight", isDaytime = false, temperatureF = 58)))!!
        assertNull(outlook.highF)
        assertEquals(58, outlook.lowF)
    }

    @Test
    fun `no precipitation numbers at all yields null, not zero`() {
        val outlook = TodayOutlook.from(
            listOf(
                period("Today", isDaytime = true, temperatureF = 75),
                period("Tonight", isDaytime = false, temperatureF = 55)
            )
        )!!
        assertNull(outlook.precipitationProbabilityPct)
    }

    @Test
    fun `an empty forecast has no outlook`() {
        assertNull(TodayOutlook.from(emptyList()))
    }
}
