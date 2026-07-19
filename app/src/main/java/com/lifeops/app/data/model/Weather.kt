package com.lifeops.app.data.model

/**
 * Weather domain models. Deliberately source-agnostic: nothing here mentions NOAA/NWS,
 * grid points, or JSON. LifeOps asks "what are current conditions?" against these plain
 * models, and the weather data layer (NwsClient → cache → WeatherRepository) is the only
 * thing that knows where the numbers actually came from.
 *
 * All temperatures are Fahrenheit, wind in mph, humidity/precipitation as whole percents —
 * the units the US NWS forecast product speaks natively, so no conversion round-trips.
 */

/** A saved place the user tracks weather for. Latitude/longitude are the identity NWS keys off. */
data class WeatherLocation(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val name: String
)

/** NWS alert severity ladder (Common Alerting Protocol). Ordered least→most urgent. */
enum class AlertSeverity(val value: String, val rank: Int) {
    UNKNOWN("Unknown", 0),
    MINOR("Minor", 1),
    MODERATE("Moderate", 2),
    SEVERE("Severe", 3),
    EXTREME("Extreme", 4);

    companion object {
        fun from(value: String?) =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Wind at a moment or over a forecast period. [directionDegrees] is meteorological (0 = from N). */
data class Wind(
    val speedMph: Int,
    val directionCardinal: String? = null,
    val gustMph: Int? = null
)

/**
 * The "right now" answer. [feelsLikeF] is computed (heat index / wind chill, see WeatherMath),
 * not a raw feed value. [uvIndex] is nullable because the NWS forecast product doesn't carry it
 * — a later phase can layer a UV source in without changing this shape.
 */
data class CurrentConditions(
    val temperatureF: Int,
    val feelsLikeF: Int,
    val humidityPct: Int?,
    val wind: Wind,
    val precipitationProbabilityPct: Int?,
    val uvIndex: Int? = null,
    val shortForecast: String,
    val observedAt: String
)

/**
 * One slice of a forecast — an hour (hourly product) or a day/night half (daily product).
 * The same shape serves both; [isDaytime] and [name] ("Tonight", "Saturday") disambiguate the
 * daily rows, while hourly rows lean on [startTime].
 */
data class ForecastPeriod(
    val name: String,
    val startTime: String,
    val endTime: String,
    val isDaytime: Boolean,
    val temperatureF: Int,
    val temperatureTrend: String? = null,
    val wind: Wind,
    val precipitationProbabilityPct: Int?,
    val humidityPct: Int? = null,
    val shortForecast: String,
    val detailedForecast: String? = null
)

/** An active NWS watch/warning/advisory for the location. */
data class WeatherAlert(
    val id: String,
    val event: String,
    val severity: AlertSeverity,
    val headline: String?,
    val description: String?,
    val instruction: String? = null,
    val onset: String? = null,
    val expires: String? = null,
    val areaDesc: String? = null
)

/**
 * The aggregate LifeOps consumes: everything known about one location at [fetchedAt]. Assembled
 * either fresh from the network or entirely from the local cache — callers can't tell which, which
 * is the whole point (opening LifeOps never waits on weather).
 */
data class WeatherReport(
    val location: WeatherLocation,
    val current: CurrentConditions,
    val hourly: List<ForecastPeriod>,
    val daily: List<ForecastPeriod>,
    val alerts: List<WeatherAlert>,
    val fetchedAt: String
) {
    /** Highest-severity active alert, if any — the thing a warning card would headline. */
    val topAlert: WeatherAlert?
        get() = alerts.maxByOrNull { it.severity.rank }
}
