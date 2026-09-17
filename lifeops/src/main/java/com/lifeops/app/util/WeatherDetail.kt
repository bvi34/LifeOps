package com.lifeops.app.util

import com.lifeops.app.data.model.CurrentConditions
import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.WeatherReport
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Turns a cached [WeatherReport] into the shape the detailed weather screen draws — pure and
 * JVM-testable, like every other thinking piece of the weather stack.
 *
 * The screen above it is deliberately dumb: it renders rows and tiles, and every decision about
 * *which* hours count as "the next day", how NWS's alternating day/night halves become one row per
 * calendar day, and when rain is worth calling out, is made and tested here.
 */

/** One hour of the hourly product, ready to render. */
data class HourRow(
    /** "Now" for the leading hour, else a local clock time like "3 PM". */
    val timeLabel: String,
    val glyph: String,
    val temperatureF: Int,
    val feelsLikeF: Int,
    val precipitationPct: Int?,
    val windMph: Int,
    val windCardinal: String?,
    val shortForecast: String,
    val isNow: Boolean
)

/** One calendar day of the daily product — NWS's day half and the night that follows it, joined. */
data class DayRow(
    /** The daytime period's own name ("Saturday"), or the night's when there is no day half. */
    val name: String,
    val glyph: String,
    /** The day half's temperature — NWS publishes it as the high. Null for a leading "Tonight". */
    val highF: Int?,
    /** The night half's temperature — the overnight low. Null for the last, unpaired day half. */
    val lowF: Int?,
    val precipitationPct: Int?,
    val shortForecast: String,
    /** The NWS prose for the day half (or the night, when that's all there is). */
    val detailedForecast: String?,
    val windLabel: String
)

/** A labelled reading for the detail grid. */
data class WeatherMetric(val label: String, val value: String, val note: String? = null)

/** Everything the detailed screen shows, already decided. */
data class WeatherDetailView(
    val hours: List<HourRow>,
    val days: List<DayRow>,
    val metrics: List<WeatherMetric>,
    /** "Rain likely around 4 PM" / "Rain likely now", or null when the window stays dry. */
    val rainArrival: String?,
    /** Coolest and warmest hour in the window — the range an hourly chart is scaled against. */
    val temperatureLowF: Int?,
    val temperatureHighF: Int?
) {
    val isEmpty: Boolean get() = hours.isEmpty() && days.isEmpty()
}

object WeatherDetail {

    /** How far ahead the hourly strip runs by default. */
    const val DEFAULT_HOURS = 24

    /** At or above this chance, the forecast is making a promise worth putting in a sentence. */
    const val RAIN_CALLOUT_PCT = 50

    private val clockFormat = DateTimeFormatter.ofPattern("h a")

    fun build(
        report: WeatherReport,
        nowIso: String = DateUtil.now(),
        hourCount: Int = DEFAULT_HOURS
    ): WeatherDetailView {
        val now = parseInstant(nowIso) ?: Instant.now()
        val hourly = hoursFrom(report.hourly, now, hourCount)
        val hours = hourly.mapIndexed { index, period ->
            HourRow(
                timeLabel = if (index == 0) "Now" else clock(period.startTime),
                glyph = WeatherGlyph.forShortForecast(period.shortForecast),
                temperatureF = period.temperatureF,
                feelsLikeF = WeatherMath.feelsLikeRounded(
                    period.temperatureF, period.humidityPct, period.wind.speedMph
                ),
                precipitationPct = period.precipitationProbabilityPct,
                windMph = period.wind.speedMph,
                windCardinal = period.wind.directionCardinal?.ifBlank { null },
                shortForecast = period.shortForecast,
                isNow = index == 0
            )
        }

        return WeatherDetailView(
            hours = hours,
            days = daysFrom(report.daily),
            metrics = metricsFor(report.current, hours, report.fetchedAt),
            rainArrival = rainArrival(hours),
            temperatureLowF = hours.minOfOrNull { it.temperatureF },
            temperatureHighF = hours.maxOfOrNull { it.temperatureF }
        )
    }

    /**
     * The hours still ahead, starting from the one you are standing in.
     *
     * That leading hour has already begun, and keeping it is the point: the current-conditions
     * header is built from exactly this period, so dropping it would label the *next* hour "Now"
     * and put the strip an hour out of step with the temperature above it.
     *
     * Two degenerate reports are handled rather than hidden. A cache old enough that every period
     * is in the past shows the whole stale window (one lonely final hour would be less honest than
     * the run of hours that make it obvious the data needs a refresh), and a report whose
     * timestamps don't parse falls back to the first [count] periods in the order they arrived.
     */
    fun hoursFrom(hourly: List<ForecastPeriod>, now: Instant, count: Int): List<ForecastPeriod> {
        if (count <= 0) return emptyList()
        val timed = hourly.mapNotNull { p -> parseInstant(p.startTime)?.let { p to it } }
        if (timed.isEmpty()) return hourly.take(count)
        val sorted = timed.sortedBy { it.second }
        val current = sorted.indexOfLast { !it.second.isAfter(now) }
        // current < 0: the whole forecast is still ahead. current == lastIndex: it is all behind.
        val from = if (current < 0 || current == sorted.lastIndex) 0 else current
        return sorted.drop(from).take(count).map { it.first }
    }

    /**
     * Fold NWS's alternating halves into one row per day. The product always runs day → night →
     * day → night, but it starts from *now*, so after sundown the first row is a night with no day
     * half (a real "Tonight" row, low only) and the last row is often a day with no night yet
     * (high only). Both are rendered honestly rather than padded with a temperature we don't have.
     */
    fun daysFrom(daily: List<ForecastPeriod>): List<DayRow> {
        val rows = mutableListOf<DayRow>()
        var i = 0
        while (i < daily.size) {
            val period = daily[i]
            if (period.isDaytime) {
                val night = daily.getOrNull(i + 1)?.takeIf { !it.isDaytime }
                rows += DayRow(
                    name = period.name,
                    glyph = WeatherGlyph.forShortForecast(period.shortForecast),
                    highF = period.temperatureF,
                    lowF = night?.temperatureF,
                    precipitationPct = listOfNotNull(
                        period.precipitationProbabilityPct,
                        night?.precipitationProbabilityPct
                    ).maxOrNull(),
                    shortForecast = period.shortForecast,
                    detailedForecast = period.detailedForecast,
                    windLabel = windLabel(period)
                )
                i += if (night != null) 2 else 1
            } else {
                rows += DayRow(
                    name = period.name,
                    glyph = WeatherGlyph.forShortForecast(period.shortForecast),
                    highF = null,
                    lowF = period.temperatureF,
                    precipitationPct = period.precipitationProbabilityPct,
                    shortForecast = period.shortForecast,
                    detailedForecast = period.detailedForecast,
                    windLabel = windLabel(period)
                )
                i += 1
            }
        }
        return rows
    }

    /**
     * The readings that don't fit on one line of a card: everything the current conditions carry,
     * plus the two facts only the hourly window knows (the next day's swing, and the gap between
     * the thermometer and what it will feel like).
     */
    fun metricsFor(
        current: CurrentConditions,
        hours: List<HourRow>,
        fetchedAt: String?
    ): List<WeatherMetric> = buildList {
        add(
            WeatherMetric(
                "Feels like",
                "${current.feelsLikeF}°",
                feelsLikeNote(current.temperatureF, current.feelsLikeF)
            )
        )
        current.humidityPct?.let { add(WeatherMetric("Humidity", "$it%")) }
        add(
            WeatherMetric(
                "Wind",
                "${current.wind.speedMph} mph",
                listOfNotNull(
                    current.wind.directionCardinal?.ifBlank { null },
                    current.wind.gustMph?.let { "gusts $it" }
                ).joinToString(" · ").ifBlank { null }
            )
        )
        current.precipitationProbabilityPct?.let { add(WeatherMetric("Rain chance", "$it%")) }
        current.uvIndex?.let { add(WeatherMetric("UV index", "$it")) }
        if (hours.isNotEmpty()) {
            val high = hours.maxOf { it.temperatureF }
            val low = hours.minOf { it.temperatureF }
            add(WeatherMetric("Next ${hours.size}h", "$high° / $low°", "high / low"))
        }
        fetchedAt?.takeIf { it.isNotBlank() }?.let {
            add(WeatherMetric("Updated", DateUtil.formatInstant(it)))
        }
    }

    /** One sentence about when the rain starts, or null while the window stays dry. */
    fun rainArrival(hours: List<HourRow>): String? {
        val first = hours.firstOrNull { (it.precipitationPct ?: 0) >= RAIN_CALLOUT_PCT } ?: return null
        val wet = if (first.glyph == "⛈️") "Storms" else "Rain"
        return if (first.isNow) "$wet likely now (${first.precipitationPct}%)"
        else "$wet likely around ${first.timeLabel} (${first.precipitationPct}%)"
    }

    private fun feelsLikeNote(actualF: Int, feelsLikeF: Int): String? = when {
        feelsLikeF - actualF >= 3 -> "humidity makes it warmer"
        actualF - feelsLikeF >= 3 -> "wind makes it colder"
        else -> null
    }

    private fun windLabel(period: ForecastPeriod): String = buildString {
        append("${period.wind.speedMph} mph")
        period.wind.directionCardinal?.takeIf { it.isNotBlank() }?.let { append(" $it") }
        period.wind.gustMph?.let { append(" · gusts $it") }
    }

    private fun clock(iso: String): String = try {
        OffsetDateTime.parse(iso).format(clockFormat)
    } catch (_: Exception) {
        try {
            ZonedDateTime.parse(iso).format(clockFormat)
        } catch (_: Exception) {
            try {
                Instant.parse(iso).atZone(ZoneId.systemDefault()).format(clockFormat)
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun parseInstant(iso: String): Instant? =
        try { Instant.parse(iso) } catch (_: Exception) {
            try { OffsetDateTime.parse(iso).toInstant() } catch (_: Exception) {
                try { ZonedDateTime.parse(iso).toInstant() } catch (_: Exception) { null }
            }
        }
}
