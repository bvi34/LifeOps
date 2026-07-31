package com.lifeops.app.util

import com.lifeops.app.data.model.AlertSeverity
import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.WeatherAlert
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Phase 5 severe-weather intelligence — pure and JVM-testable. Combines active NOAA alerts with the
 * hourly forecast to turn "there's a storm" into something actionable: *when* it hits, *when* it
 * clears, and roughly how long to delay outdoor plans. No radar-pixel analysis (out of scope for the
 * keyless NWS feed) — storm timing comes from alert expiry and the hourly forecast's own wording.
 */
data class WeatherAdvisory(
    val headline: String,
    val detail: String,
    /** How long until conditions are expected to clear, if known. */
    val suggestedDelayMinutes: Int?,
    /** Ordering weight (AlertSeverity.rank). */
    val severityRank: Int
) {
    /** "Delay ~90 min" style hint, or null when no clear-by time is known. */
    val delayHint: String?
        get() = suggestedDelayMinutes?.let { m ->
            when {
                m >= 120 -> "Delay ~${m / 60}h"
                m >= 60 -> "Delay ~1h${if (m % 60 != 0) " ${m % 60}m" else ""}"
                else -> "Delay ~${m} min"
            }
        }
}

object SevereWeatherIntel {

    private val clockFormat = DateTimeFormatter.ofPattern("h:mm a")

    /** How far ahead an upcoming forecast storm still counts as "approaching". */
    private const val APPROACHING_HOURS = 6L
    private const val SCAN_HOURS = 12L

    fun advise(alerts: List<WeatherAlert>, hourly: List<ForecastPeriod>, nowIso: String): List<WeatherAdvisory> {
        val now = parseInstant(nowIso) ?: return emptyList()
        val out = mutableListOf<WeatherAdvisory>()

        val stormAlerts = alerts.filter { it.indicatesStorm() }.sortedByDescending { it.severity.rank }
        for (a in stormAlerts) {
            val expires = a.expires?.let { parseInstant(it) }
            val delay = expires?.let { Duration.between(now, it).toMinutes() }?.takeIf { it > 0 }?.toInt()
            val detail = buildString {
                append(a.headline?.ifBlank { a.event } ?: a.event.ifBlank { "Severe weather" })
                if (expires != null && delay != null) append(" · in effect until ${clock(a.expires!!)}")
            }
            out += WeatherAdvisory(
                headline = a.event.ifBlank { "Severe weather alert" },
                detail = detail,
                suggestedDelayMinutes = delay,
                severityRank = a.severity.rank
            )
        }

        // Forecast-driven storm, only when no alert already covers it.
        if (stormAlerts.isEmpty()) {
            forecastStormAdvisory(hourly, now)?.let { out += it }
        }
        return out
    }

    private fun forecastStormAdvisory(hourly: List<ForecastPeriod>, now: Instant): WeatherAdvisory? {
        val timed = hourly
            .mapNotNull { p -> parseInstant(p.startTime)?.let { t -> p to t } }
            .filter { (_, t) -> t.isAfter(now.minusSeconds(3600)) && t.isBefore(now.plusSeconds(SCAN_HOURS * 3600)) }
            .sortedBy { it.second }

        val firstStormIdx = timed.indexOfFirst { it.first.isStormy() }
        if (firstStormIdx < 0) return null

        val (stormPeriod, stormStart) = timed[firstStormIdx]
        if (stormStart.isAfter(now.plusSeconds(APPROACHING_HOURS * 3600))) return null // too far out to act on

        val clearAfter = timed.drop(firstStormIdx).firstOrNull { !it.first.isStormy() }
        val delay = clearAfter?.let { Duration.between(now, it.second).toMinutes() }?.takeIf { it > 0 }?.toInt()

        val inProgress = !stormStart.isAfter(now.plusSeconds(1800))
        val detail = buildString {
            append(stormPeriod.shortForecast.ifBlank { "Storms" })
            append(if (inProgress) " now" else " around ${clock(stormPeriod.startTime)}")
            clearAfter?.let { append("; clearing by ${clock(it.first.startTime)}") }
        }
        return WeatherAdvisory(
            headline = if (inProgress) "Storm in progress" else "Storm approaching",
            detail = detail,
            suggestedDelayMinutes = delay,
            severityRank = AlertSeverity.MODERATE.rank
        )
    }

    private fun WeatherAlert.indicatesStorm(): Boolean =
        severity.rank >= AlertSeverity.SEVERE.rank ||
            event.contains("storm", ignoreCase = true) ||
            event.contains("tornado", ignoreCase = true)

    private fun ForecastPeriod.isStormy(): Boolean = OutdoorScore.stormRiskFromText(shortForecast)

    private fun clock(iso: String): String = try {
        OffsetDateTime.parse(iso).format(clockFormat)
    } catch (_: Exception) {
        try { ZonedDateTime.parse(iso).format(clockFormat) } catch (_: Exception) { "" }
    }

    private fun parseInstant(iso: String): Instant? =
        try { Instant.parse(iso) } catch (_: Exception) {
            try { OffsetDateTime.parse(iso).toInstant() } catch (_: Exception) {
                try { ZonedDateTime.parse(iso).toInstant() } catch (_: Exception) { null }
            }
        }
}
