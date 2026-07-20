package com.lifeops.app.util

import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.TaskWeatherRequirement
import com.lifeops.app.data.model.Wind
import org.junit.Assert.*
import org.junit.Test

class TaskWeatherFitTest {

    private fun period(
        name: String,
        tempF: Int,
        precip: Int? = 0,
        windMph: Int = 5,
        forecast: String = "Sunny",
        start: String
    ) = ForecastPeriod(
        name = name, startTime = start, endTime = start, isDaytime = true,
        temperatureF = tempF, temperatureTrend = null,
        wind = Wind(windMph, "S"), precipitationProbabilityPct = precip,
        humidityPct = 45, shortForecast = forecast
    )

    @Test
    fun `no fit for an empty requirement`() {
        val periods = listOf(period("Today", 72, start = "2026-07-20T08:00:00-05:00"))
        assertNull(TaskWeatherFitCalculator.compute(TaskWeatherRequirement(taskId = "t"), periods))
    }

    @Test
    fun `no fit when there is no forecast`() {
        val req = TaskWeatherRequirement(taskId = "t", outdoorPreferred = true, avoidRain = true)
        assertNull(TaskWeatherFitCalculator.compute(req, emptyList()))
    }

    @Test
    fun `wet today points at the next dry day`() {
        val req = TaskWeatherRequirement(taskId = "t", outdoorPreferred = true, avoidRain = true)
        val periods = listOf(
            period("Today", 74, precip = 70, forecast = "Rain Likely", start = "2026-07-20T08:00:00-05:00"),
            period("Tuesday", 74, precip = 5, start = "2026-07-21T08:00:00-05:00")
        )
        val fit = TaskWeatherFitCalculator.compute(req, periods)!!
        assertFalse(fit.suitableToday)
        assertFalse(fit.bestIsToday)
        assertEquals("Tuesday", fit.bestWindowLabel)
        assertNotNull(fit.notTodayReason)
        assertTrue(fit.notTodayReason!!.contains("Rain"))
    }

    @Test
    fun `good today reports itself as the best window`() {
        val req = TaskWeatherRequirement(taskId = "t", outdoorPreferred = true, avoidRain = true, maxTempF = 85)
        val periods = listOf(
            period("Today", 72, precip = 5, start = "2026-07-20T08:00:00-05:00"),
            period("Tuesday", 95, precip = 0, start = "2026-07-21T08:00:00-05:00")
        )
        val fit = TaskWeatherFitCalculator.compute(req, periods)!!
        assertTrue(fit.suitableToday)
        assertTrue(fit.bestIsToday)
        assertNull(fit.notTodayReason)
    }

    @Test
    fun `nothing suitable all week leaves no best window`() {
        val req = TaskWeatherRequirement(taskId = "t", outdoorPreferred = true, maxTempF = 60)
        val periods = listOf(
            period("Today", 95, start = "2026-07-20T08:00:00-05:00"),
            period("Tuesday", 92, start = "2026-07-21T08:00:00-05:00")
        )
        val fit = TaskWeatherFitCalculator.compute(req, periods)!!
        assertFalse(fit.suitableToday)
        assertNull(fit.bestWindowLabel)
    }
}
