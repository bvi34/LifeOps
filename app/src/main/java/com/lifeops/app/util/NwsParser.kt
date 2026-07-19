package com.lifeops.app.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lifeops.app.data.model.AlertSeverity
import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.Wind

/**
 * Pure NWS (api.weather.gov) response parsing — raw JSON string in, LifeOps models out. No
 * network, no Android: the HTTP hop lives in NwsClient, so everything here is unit-testable
 * against captured payloads (see NwsParserTest). Gson is already a project dependency.
 *
 * The NWS flow this parses:
 *   1. GET /points/{lat},{lon}      → the forecast grid + hourly/daily forecast URLs  (parseGridPoint)
 *   2. GET {forecastHourly}         → hourly ForecastPeriods                          (parseForecastPeriods)
 *   3. GET {forecast}               → day/night ForecastPeriods                       (parseForecastPeriods)
 *   4. GET /alerts/active?point=... → active WeatherAlerts                            (parseAlerts)
 */
object NwsParser {
    private val gson = Gson()

    /** The grid coordinates + forecast URLs a /points lookup resolves a lat/lon to. */
    data class GridPoint(
        val gridId: String,
        val gridX: Int,
        val gridY: Int,
        val forecastUrl: String,
        val forecastHourlyUrl: String,
        val timeZone: String?,
        val city: String?,
        val state: String?
    ) {
        /** "Topeka, KS" when both are present, else whichever exists, else null. */
        val placeName: String?
            get() = when {
                city != null && state != null -> "$city, $state"
                else -> city ?: state
            }
    }

    fun parseGridPoint(json: String): GridPoint {
        val dto = gson.fromJson(json, PointDto::class.java)
        val p = dto?.properties ?: error("NWS /points response had no properties")
        return GridPoint(
            gridId = p.gridId.orEmpty(),
            gridX = p.gridX ?: 0,
            gridY = p.gridY ?: 0,
            forecastUrl = p.forecast.orEmpty(),
            forecastHourlyUrl = p.forecastHourly.orEmpty(),
            timeZone = p.timeZone,
            city = p.relativeLocation?.properties?.city,
            state = p.relativeLocation?.properties?.state
        )
    }

    /** Parse either the hourly or the day/night forecast product into ordered periods. */
    fun parseForecastPeriods(json: String): List<ForecastPeriod> {
        val dto = gson.fromJson(json, ForecastDto::class.java)
        val periods = dto?.properties?.periods ?: return emptyList()
        return periods.map { it.toModel() }
    }

    fun parseAlerts(json: String): List<WeatherAlert> {
        val dto = gson.fromJson(json, AlertsDto::class.java)
        val features = dto?.features ?: return emptyList()
        return features.mapNotNull { feature ->
            val p = feature.properties ?: return@mapNotNull null
            WeatherAlert(
                id = feature.id ?: p.id ?: return@mapNotNull null,
                event = p.event.orEmpty(),
                severity = AlertSeverity.from(p.severity),
                headline = p.headline,
                description = p.description,
                instruction = p.instruction,
                onset = p.onset,
                expires = p.expires,
                areaDesc = p.areaDesc
            )
        }
    }

    /**
     * NWS reports wind speed as human text: "10 mph", "5 to 10 mph", "15 mph". We take the
     * strongest number in the string (the gust-ward end of a range is what a "max wind" rule
     * should key off). Returns 0 when nothing numeric is present ("Calm").
     */
    fun parseWindMph(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return Regex("\\d+").findAll(text).map { it.value.toInt() }.maxOrNull() ?: 0
    }

    private fun PeriodDto.toModel(): ForecastPeriod {
        val windMph = NwsParser.parseWindMph(windSpeed)
        return ForecastPeriod(
            name = name.orEmpty(),
            startTime = startTime.orEmpty(),
            endTime = endTime.orEmpty(),
            isDaytime = isDaytime ?: true,
            temperatureF = temperature ?: 0,
            temperatureTrend = temperatureTrend,
            wind = Wind(speedMph = windMph, directionCardinal = windDirection),
            precipitationProbabilityPct = probabilityOfPrecipitation?.value?.let { it.toInt() },
            humidityPct = relativeHumidity?.value?.let { it.toInt() },
            shortForecast = shortForecast.orEmpty(),
            detailedForecast = detailedForecast
        )
    }

    // --- Gson DTOs. Field names mirror the api.weather.gov JSON keys. ---

    private data class PointDto(val properties: PointProps?)
    private data class PointProps(
        val gridId: String?,
        val gridX: Int?,
        val gridY: Int?,
        val forecast: String?,
        val forecastHourly: String?,
        val timeZone: String?,
        val relativeLocation: RelativeLocation?
    )
    private data class RelativeLocation(val properties: RelativeLocationProps?)
    private data class RelativeLocationProps(val city: String?, val state: String?)

    private data class ForecastDto(val properties: ForecastProps?)
    private data class ForecastProps(val periods: List<PeriodDto>?)
    private data class PeriodDto(
        val name: String?,
        val startTime: String?,
        val endTime: String?,
        val isDaytime: Boolean?,
        val temperature: Int?,
        val temperatureUnit: String?,
        val temperatureTrend: String?,
        val probabilityOfPrecipitation: UnitValue?,
        val relativeHumidity: UnitValue?,
        val windSpeed: String?,
        val windDirection: String?,
        val shortForecast: String?,
        val detailedForecast: String?
    )

    /** NWS wraps most measurements as { "unitCode": "...", "value": 20 }; value may be null. */
    private data class UnitValue(val value: Double?)

    private data class AlertsDto(val features: List<AlertFeature>?)
    private data class AlertFeature(val id: String?, val properties: AlertProps?)
    private data class AlertProps(
        val id: String?,
        val event: String?,
        val severity: String?,
        val headline: String?,
        val description: String?,
        val instruction: String?,
        val onset: String?,
        val expires: String?,
        @SerializedName("areaDesc") val areaDesc: String?
    )
}
