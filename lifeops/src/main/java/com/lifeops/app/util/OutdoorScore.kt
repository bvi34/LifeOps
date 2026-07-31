package com.lifeops.app.util

import com.lifeops.app.data.model.AlertSeverity
import com.lifeops.app.data.model.CurrentConditions
import com.lifeops.app.data.model.WeatherAlert

/**
 * Rules-based "how good is it outside right now?" scoring — Phase 2 of the weather roadmap. Pure
 * and Android-free (like WeatherMath / ScoringUtils) so it's unit-testable on the JVM.
 *
 * The score is a 0–100 DISCOMFORT total: higher = worse, so it maps directly onto the roadmap's
 * bands (0–25 Excellent … 76+ Avoid). Each factor contributes independent penalty points, which
 * are summed and clamped; alongside the number we return human-readable positives/warnings that
 * the Phase 3 cards and "best time" reasons can show verbatim.
 */
enum class OutdoorRating(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    CAUTION("Caution"),
    AVOID("Avoid");

    companion object {
        fun fromScore(score: Int) = when {
            score <= 25 -> EXCELLENT
            score <= 50 -> GOOD
            score <= 75 -> CAUTION
            else -> AVOID
        }
    }
}

data class OutdoorAssessment(
    /** 0–100 discomfort total; higher is worse. */
    val score: Int,
    val rating: OutdoorRating,
    /** "✓"-style good points, e.g. "Comfortable temperature (72°)". */
    val positives: List<String>,
    /** Things pushing the score up, e.g. "High UV (9)". */
    val warnings: List<String>
)

object OutdoorScore {

    /**
     * Score raw conditions. All the "feels like" heat/cold is expected to already be baked into
     * [feelsLikeF] (see WeatherMath) — that's the primary driver; humidity only adds a little on
     * top when it's warm and muggy. Missing inputs (null) simply contribute nothing.
     */
    fun score(
        feelsLikeF: Int,
        humidityPct: Int?,
        uvIndex: Int?,
        windMph: Int,
        rainProbabilityPct: Int?,
        stormRisk: Boolean
    ): OutdoorAssessment {
        var total = 0
        val positives = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // --- Feels-like temperature (primary factor) ---
        val tempPenalty = feelsLikePenalty(feelsLikeF)
        total += tempPenalty
        when {
            tempPenalty == 0 -> positives += "Comfortable temperature (${feelsLikeF}°)"
            feelsLikeF > 80 -> warnings += "Hot — feels like ${feelsLikeF}°"
            else -> warnings += "Cold — feels like ${feelsLikeF}°"
        }

        // --- Humidity (only bites when it's already warm) ---
        if (humidityPct != null && humidityPct >= 70 && feelsLikeF >= 80) {
            total += (humidityPct - 70) / 3
            warnings += "Humid (${humidityPct}%)"
        }

        // --- UV ---
        if (uvIndex != null) {
            total += uvPenalty(uvIndex)
            when {
                uvIndex >= 8 -> warnings += "High UV ($uvIndex)"
                uvIndex <= 2 -> positives += "Low UV ($uvIndex)"
            }
        }

        // --- Wind ---
        total += windPenalty(windMph)
        when {
            windMph >= 16 -> warnings += "Windy (${windMph} mph)"
            windMph < 8 -> positives += "Light wind"
        }

        // --- Rain ---
        if (rainProbabilityPct != null) {
            total += rainPenalty(rainProbabilityPct)
            when {
                rainProbabilityPct >= 50 -> warnings += "Likely rain (${rainProbabilityPct}%)"
                rainProbabilityPct <= 20 -> positives += "Low rain chance (${rainProbabilityPct}%)"
            }
        }

        // --- Storms trump everything: a single strong penalty pushes to Avoid on its own. ---
        if (stormRisk) {
            total += STORM_PENALTY
            warnings += "Storm risk"
        }

        val clamped = total.coerceIn(0, 100)
        return OutdoorAssessment(clamped, OutdoorRating.fromScore(clamped), positives, warnings)
    }

    /** Assess a cached [CurrentConditions], folding in storm signals from the forecast text and
     *  any active [alerts] (severe/tornado/thunderstorm). */
    fun forCurrent(current: CurrentConditions, alerts: List<WeatherAlert> = emptyList()): OutdoorAssessment {
        val storm = stormRiskFromText(current.shortForecast) || alerts.any { it.indicatesStorm() }
        return score(
            feelsLikeF = current.feelsLikeF,
            humidityPct = current.humidityPct,
            uvIndex = current.uvIndex,
            windMph = current.wind.speedMph,
            rainProbabilityPct = current.precipitationProbabilityPct,
            stormRisk = storm
        )
    }

    fun stormRiskFromText(text: String?): Boolean {
        val t = text?.lowercase() ?: return false
        return "thunder" in t || "storm" in t
    }

    private fun WeatherAlert.indicatesStorm(): Boolean =
        severity.rank >= AlertSeverity.SEVERE.rank ||
            event.contains("storm", ignoreCase = true) ||
            event.contains("tornado", ignoreCase = true)

    // --- Piecewise penalty curves (all return non-negative points) ---

    private fun feelsLikePenalty(f: Int): Int = when {
        f in 55..80 -> 0
        f in 81..89 -> (f - 80) * 2          // 2..18
        f in 90..99 -> 20 + (f - 90) * 3     // 20..47
        f >= 100 -> 50 + (f - 100) * 3       // 50.. (clamped by caller)
        f in 45..54 -> (55 - f) * 2          // 2..20
        f in 30..44 -> 22 + (44 - f) * 2     // 22..50
        else -> 55 + (30 - f) * 2            // very cold (f < 30)
    }

    private fun uvPenalty(uv: Int): Int = when {
        uv <= 2 -> 0
        uv in 3..5 -> 4
        uv in 6..7 -> 10
        uv in 8..10 -> 18
        else -> 26
    }

    private fun windPenalty(w: Int): Int = when {
        w < 8 -> 0
        w in 8..15 -> w - 7          // 1..8
        w in 16..25 -> 8 + (w - 15) * 2  // 10..28
        else -> 28 + (w - 25)        // 28..
    }

    private fun rainPenalty(p: Int): Int = when {
        p <= 20 -> 0
        p in 21..50 -> (p - 20) / 2  // 0..15
        p in 51..70 -> 15 + (p - 50) / 2 // 15..25
        else -> 25 + (p - 70) / 2    // 25..
    }

    private const val STORM_PENALTY = 75
}
