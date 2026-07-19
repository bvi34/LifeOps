package com.lifeops.app.util

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure, Android-free "feels like" math — the apparent-temperature layer the NWS forecast feed
 * doesn't hand you directly. Lives in util/ (like ScoringUtils / NutritionCalculator) so it's
 * unit-testable on the JVM. Everything is Fahrenheit / mph / whole-percent to match the models.
 */
object WeatherMath {

    /**
     * NWS heat index (Rothfusz regression) in °F, valid where it meaningfully departs from the
     * air temperature: warm and not bone-dry. Below ~80°F the regression isn't used, so we return
     * the air temperature unchanged. Mirrors the adjustments the NWS applies at humidity extremes.
     */
    fun heatIndexF(temperatureF: Double, humidityPct: Double): Double {
        if (temperatureF < 80.0) return temperatureF
        val rh = humidityPct.coerceIn(0.0, 100.0)

        // Simple form first; if it lands below 80 the full regression isn't warranted.
        val simple = 0.5 * (temperatureF + 61.0 + (temperatureF - 68.0) * 1.2 + rh * 0.094)
        if ((simple + temperatureF) / 2.0 < 80.0) return simple

        var hi = -42.379 +
            2.04901523 * temperatureF +
            10.14333127 * rh -
            0.22475541 * temperatureF * rh -
            0.00683783 * temperatureF * temperatureF -
            0.05481717 * rh * rh +
            0.00122874 * temperatureF * temperatureF * rh +
            0.00085282 * temperatureF * rh * rh -
            0.00000199 * temperatureF * temperatureF * rh * rh

        // Low-humidity correction (subtract) and high-humidity correction (add).
        if (rh < 13.0 && temperatureF in 80.0..112.0) {
            hi -= ((13.0 - rh) / 4.0) * sqrt((17.0 - kotlin.math.abs(temperatureF - 95.0)) / 17.0)
        } else if (rh > 85.0 && temperatureF in 80.0..87.0) {
            hi += ((rh - 85.0) / 10.0) * ((87.0 - temperatureF) / 5.0)
        }
        return hi
    }

    /**
     * NWS wind chill in °F, valid at or below 50°F with wind above 3 mph. Outside that envelope
     * wind chill isn't defined, so the air temperature is returned unchanged.
     */
    fun windChillF(temperatureF: Double, windMph: Double): Double {
        if (temperatureF > 50.0 || windMph <= 3.0) return temperatureF
        val v = Math.pow(windMph, 0.16)
        return 35.74 + 0.6215 * temperatureF - 35.75 * v + 0.4275 * temperatureF * v
    }

    /**
     * The single "feels like" number LifeOps shows: heat index in the heat, wind chill in the
     * cold, plain air temperature in the mild middle. [humidityPct] / [windMph] may be null when
     * the feed omits them, in which case the corresponding adjustment is skipped.
     */
    fun feelsLikeF(temperatureF: Double, humidityPct: Double?, windMph: Double?): Double = when {
        temperatureF >= 80.0 && humidityPct != null -> heatIndexF(temperatureF, humidityPct)
        temperatureF <= 50.0 && windMph != null -> windChillF(temperatureF, windMph)
        else -> temperatureF
    }

    /** Convenience: rounded whole-degree feels-like for the integer-valued models. */
    fun feelsLikeRounded(temperatureF: Int, humidityPct: Int?, windMph: Int?): Int =
        feelsLikeF(temperatureF.toDouble(), humidityPct?.toDouble(), windMph?.toDouble()).roundToInt()
}
