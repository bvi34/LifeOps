package com.lifeops.app.util

import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.data.model.Wind
import org.junit.Assert.*
import org.junit.Test

class BestTimeTest {

    private fun period(
        name: String,
        tempF: Int,
        precip: Int? = 0,
        windMph: Int = 5,
        humidity: Int? = 45,
        forecast: String = "Sunny",
        start: String = "2026-07-18T08:00:00-05:00"
    ) = ForecastPeriod(
        name = name, startTime = start, endTime = start, isDaytime = true,
        temperatureF = tempF, temperatureTrend = null,
        wind = Wind(windMph, "S"), precipitationProbabilityPct = precip,
        humidityPct = humidity, shortForecast = forecast
    )

    @Test
    fun `best window respects a task max temperature`() {
        val req = TaskWeatherRequirement(taskId = "t1", outdoorPreferred = true, maxTempF = 85)
        val periods = listOf(
            period("Sunday", tempF = 95),
            period("Saturday", tempF = 72)
        )
        val best = BestTime.best(req, periods)
        assertNotNull(best)
        assertEquals("Saturday", best!!.label)

        val ranked = BestTime.recommend(req, periods)
        val sunday = ranked.first { it.label == "Sunday" }
        assertTrue(sunday.disqualified)
        assertTrue(sunday.disqualifiers.any { it.contains("Too warm") })
    }

    @Test
    fun `avoid-rain disqualifies wet and stormy windows`() {
        val req = TaskWeatherRequirement(taskId = "t2", outdoorPreferred = true, avoidRain = true)
        val dry = period("Fri", tempF = 74, precip = 10)
        val wet = period("Sat", tempF = 74, precip = 60)
        val stormy = period("Sun", tempF = 74, precip = 30, forecast = "Scattered Thunderstorms")

        val ranked = BestTime.recommend(req, listOf(dry, wet, stormy))
        assertFalse(ranked.first { it.label == "Fri" }.disqualified)
        assertTrue(ranked.first { it.label == "Sat" }.disqualifiers.any { it.contains("Rain likely") })
        assertTrue(ranked.first { it.label == "Sun" }.disqualifiers.any { it.contains("Storm") })
    }

    @Test
    fun `a person's heat ceiling rules a window out`() {
        val req = TaskWeatherRequirement(taskId = "t3", outdoorPreferred = true)
        val child = Person(id = "p1", name = "Kid", heatToleranceMaxF = 90, createdAt = "2026-01-01T00:00:00Z")
        // 95°F at 55% RH -> heat index well over 90.
        val hot = period("Noon", tempF = 95, humidity = 55)
        val best = BestTime.best(req, listOf(hot), people = listOf(child))
        assertNull(best)
        val only = BestTime.recommend(req, listOf(hot), people = listOf(child)).first()
        assertTrue(only.disqualifiers.any { it.contains("Too hot for Kid") })
    }

    @Test
    fun `match percent is high for a pleasant eligible window`() {
        val req = TaskWeatherRequirement(taskId = "t4", outdoorPreferred = true)
        val best = BestTime.best(req, listOf(period("Sat", tempF = 72)))
        assertNotNull(best)
        assertTrue("expected a strong match, got ${best!!.matchPercent}", best.matchPercent >= 80)
        assertEquals(OutdoorRating.EXCELLENT, best.rating)
    }

    @Test
    fun `a busy calendar label removes that window`() {
        val req = TaskWeatherRequirement(taskId = "t5", outdoorPreferred = true)
        val ranked = BestTime.recommend(
            req, listOf(period("Saturday", tempF = 72)), busyLabels = setOf("Saturday")
        )
        assertTrue(ranked.first().disqualified)
        assertTrue(ranked.first().disqualifiers.contains("Calendar conflict"))
    }
}
