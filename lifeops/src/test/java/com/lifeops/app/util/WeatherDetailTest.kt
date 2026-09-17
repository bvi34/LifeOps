package com.lifeops.app.util

import com.lifeops.app.data.model.CurrentConditions
import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.WeatherLocation
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.data.model.Wind
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WeatherDetailTest {

    private val now = Instant.parse("2026-07-18T13:00:00Z")

    private fun hour(
        offsetHours: Long,
        tempF: Int,
        precip: Int? = 0,
        forecast: String = "Sunny",
        windMph: Int = 6,
        humidity: Int? = 40
    ) = ForecastPeriod(
        name = "",
        startTime = now.plusSeconds(offsetHours * 3600).toString(),
        endTime = now.plusSeconds((offsetHours + 1) * 3600).toString(),
        isDaytime = true,
        temperatureF = tempF,
        wind = Wind(windMph, "SW"),
        precipitationProbabilityPct = precip,
        humidityPct = humidity,
        shortForecast = forecast
    )

    private fun half(
        name: String,
        daytime: Boolean,
        tempF: Int,
        precip: Int? = 0,
        forecast: String = "Sunny",
        detailed: String? = "A long NWS sentence."
    ) = ForecastPeriod(
        name = name,
        startTime = now.toString(),
        endTime = now.toString(),
        isDaytime = daytime,
        temperatureF = tempF,
        wind = Wind(9, "N", gustMph = 20),
        precipitationProbabilityPct = precip,
        humidityPct = 50,
        shortForecast = forecast,
        detailedForecast = detailed
    )

    private fun report(
        hourly: List<ForecastPeriod> = listOf(hour(0, 88), hour(1, 90), hour(2, 91)),
        daily: List<ForecastPeriod> = emptyList(),
        current: CurrentConditions = CurrentConditions(
            temperatureF = 88, feelsLikeF = 95, humidityPct = 65,
            wind = Wind(6, "SW", gustMph = 18), precipitationProbabilityPct = 20,
            uvIndex = 7, shortForecast = "Sunny", observedAt = now.toString()
        )
    ) = WeatherReport(
        location = WeatherLocation("loc", 35.4676, -97.5164, "Oklahoma City"),
        current = current,
        hourly = hourly,
        daily = daily,
        alerts = emptyList(),
        fetchedAt = now.toString()
    )

    // --- The hourly window ---

    @Test
    fun `the window starts at the hour you are standing in`() {
        val hourly = listOf(hour(-3, 80), hour(-2, 82), hour(-1, 84), hour(0, 88), hour(1, 90))
        val window = WeatherDetail.hoursFrom(hourly, now, count = 24)
        // The hour that began up to an hour ago is "now"; anything older is behind us.
        assertEquals(2, window.size)
        assertEquals(88, window.first().temperatureF)
    }

    @Test
    fun `the window is capped at the requested length`() {
        val hourly = (0L until 48L).map { hour(it, 70 + it.toInt() % 10) }
        assertEquals(24, WeatherDetail.hoursFrom(hourly, now, count = 24).size)
        assertEquals(6, WeatherDetail.hoursFrom(hourly, now, count = 6).size)
        assertTrue(WeatherDetail.hoursFrom(hourly, now, count = 0).isEmpty())
    }

    @Test
    fun `an entirely past forecast still shows something rather than nothing`() {
        val hourly = listOf(hour(-30, 60), hour(-29, 62))
        val window = WeatherDetail.hoursFrom(hourly, now, count = 24)
        assertEquals(2, window.size)
    }

    @Test
    fun `unparseable timestamps fall back to the order they came in`() {
        val broken = listOf(hour(0, 70).copy(startTime = "whenever"), hour(1, 72).copy(startTime = ""))
        assertEquals(2, WeatherDetail.hoursFrom(broken, now, count = 24).size)
    }

    @Test
    fun `the leading hour is labelled Now and carries a feels-like of its own`() {
        val view = WeatherDetail.build(report(), nowIso = now.toString())
        assertEquals("Now", view.hours.first().timeLabel)
        assertTrue(view.hours.first().isNow)
        assertFalse(view.hours[1].isNow)
        assertTrue(view.hours[1].timeLabel.isNotBlank())
        // 88° at 40% humidity is the heat index, not the raw reading.
        assertTrue(view.hours.first().feelsLikeF >= 88)
    }

    // --- Day/night folding ---

    @Test
    fun `a day half and the night after it become one row`() {
        val daily = listOf(
            half("Saturday", daytime = true, tempF = 88, precip = 20),
            half("Saturday Night", daytime = false, tempF = 64, precip = 60),
            half("Sunday", daytime = true, tempF = 91),
            half("Sunday Night", daytime = false, tempF = 68)
        )
        val rows = WeatherDetail.daysFrom(daily)
        assertEquals(2, rows.size)
        assertEquals("Saturday", rows[0].name)
        assertEquals(88, rows[0].highF)
        assertEquals(64, rows[0].lowF)
        // The worst chance across the whole day is what the row reports.
        assertEquals(60, rows[0].precipitationPct)
        assertEquals("Sunday", rows[1].name)
    }

    @Test
    fun `an evening forecast opens with a night-only row`() {
        val daily = listOf(
            half("Tonight", daytime = false, tempF = 66),
            half("Tuesday", daytime = true, tempF = 84),
            half("Tuesday Night", daytime = false, tempF = 65)
        )
        val rows = WeatherDetail.daysFrom(daily)
        assertEquals(2, rows.size)
        assertEquals("Tonight", rows[0].name)
        assertNull(rows[0].highF)
        assertEquals(66, rows[0].lowF)
        assertEquals(84, rows[1].highF)
    }

    @Test
    fun `a trailing day half with no night yet keeps its high and no invented low`() {
        val rows = WeatherDetail.daysFrom(listOf(half("Friday", daytime = true, tempF = 79)))
        assertEquals(1, rows.size)
        assertEquals(79, rows[0].highF)
        assertNull(rows[0].lowF)
        assertTrue(rows[0].windLabel.contains("gusts"))
    }

    @Test
    fun `no daily product means no day rows rather than a crash`() {
        assertTrue(WeatherDetail.daysFrom(emptyList()).isEmpty())
    }

    // --- Metrics and callouts ---

    @Test
    fun `metrics cover the readings and explain the feels-like gap`() {
        val view = WeatherDetail.build(report(), nowIso = now.toString())
        val labels = view.metrics.map { it.label }
        assertTrue(labels.containsAll(listOf("Feels like", "Humidity", "Wind", "Rain chance", "UV index")))
        assertEquals("humidity makes it warmer", view.metrics.first { it.label == "Feels like" }.note)
        assertTrue(view.metrics.first { it.label == "Wind" }.note!!.contains("gusts 18"))
        assertTrue(labels.any { it.startsWith("Next ") })
        assertTrue(labels.contains("Updated"))
    }

    @Test
    fun `a missing reading simply has no tile`() {
        val bare = CurrentConditions(
            temperatureF = 50, feelsLikeF = 44, humidityPct = null,
            wind = Wind(20), precipitationProbabilityPct = null, uvIndex = null,
            shortForecast = "Windy", observedAt = now.toString()
        )
        val view = WeatherDetail.build(report(current = bare), nowIso = now.toString())
        val labels = view.metrics.map { it.label }
        assertFalse(labels.contains("Humidity"))
        assertFalse(labels.contains("UV index"))
        assertFalse(labels.contains("Rain chance"))
        assertEquals("wind makes it colder", view.metrics.first { it.label == "Feels like" }.note)
        assertNull(view.metrics.first { it.label == "Wind" }.note)
    }

    @Test
    fun `rain is called out with the hour it arrives`() {
        val hourly = listOf(hour(0, 86, precip = 10), hour(1, 85, precip = 30), hour(2, 82, precip = 70))
        val view = WeatherDetail.build(report(hourly = hourly), nowIso = now.toString())
        val arrival = view.rainArrival
        assertNotNull(arrival)
        assertTrue(arrival!!.startsWith("Rain likely around"))
        assertTrue(arrival.contains("70%"))
    }

    @Test
    fun `rain already falling reads as now, and storms say storms`() {
        val hourly = listOf(hour(0, 75, precip = 80, forecast = "Thunderstorms"), hour(1, 74, precip = 60))
        val view = WeatherDetail.build(report(hourly = hourly), nowIso = now.toString())
        assertEquals("Storms likely now (80%)", view.rainArrival)
    }

    @Test
    fun `a dry window says nothing about rain`() {
        val hourly = listOf(hour(0, 80, precip = 10), hour(1, 81, precip = 0), hour(2, 82, precip = null))
        assertNull(WeatherDetail.build(report(hourly = hourly), nowIso = now.toString()).rainArrival)
    }

    @Test
    fun `the temperature range spans the window for the chart to scale against`() {
        val hourly = listOf(hour(0, 71), hour(1, 88), hour(2, 79))
        val view = WeatherDetail.build(report(hourly = hourly), nowIso = now.toString())
        assertEquals(71, view.temperatureLowF)
        assertEquals(88, view.temperatureHighF)
    }

    @Test
    fun `an empty report is empty rather than broken`() {
        val view = WeatherDetail.build(report(hourly = emptyList()), nowIso = now.toString())
        assertTrue(view.hours.isEmpty())
        assertTrue(view.isEmpty)
        assertNull(view.rainArrival)
        assertNull(view.temperatureHighF)
        // The current-conditions tiles survive an empty forecast.
        assertTrue(view.metrics.any { it.label == "Feels like" })
    }
}
