package com.lifeops.app.data.weather

import com.lifeops.app.data.model.CurrentConditions
import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.WeatherAlert
import com.lifeops.app.data.model.WeatherLocation
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.NwsParser
import com.lifeops.app.util.WeatherMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Live client for the US National Weather Service API (api.weather.gov) — the only class in the
 * weather stack that touches the network. All parsing is delegated to the pure [NwsParser], so
 * this file stays a thin, dependency-light HTTP shell built on HttpURLConnection (no OkHttp/
 * Retrofit added). Every call suspends onto Dispatchers.IO; callers (WeatherRepository) treat a
 * thrown [WeatherException] as "couldn't refresh — keep serving cache".
 *
 * The NWS API is free and keyless but REQUIRES a descriptive User-Agent identifying the app; a
 * missing/blank one is rejected. It's a public, US-only service — nothing here sends user data.
 */
class NwsClient(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val baseUrl: String = "https://api.weather.gov"
) {

    class WeatherException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Full fetch for [location]: resolve the grid, pull hourly + daily forecasts and active
     * alerts, and assemble a [WeatherReport]. "Current conditions" are derived from the first
     * hourly period (a keyless, single-unit-system proxy; real station observations can be
     * layered in later). Throws [WeatherException] on any network/parse failure.
     */
    suspend fun fetchReport(location: WeatherLocation): WeatherReport = withContext(Dispatchers.IO) {
        // NWS wants at most 4 decimals on /points, else it 301-redirects.
        val lat = String.format(Locale.US, "%.4f", location.latitude)
        val lon = String.format(Locale.US, "%.4f", location.longitude)

        val grid = NwsParser.parseGridPoint(get("$baseUrl/points/$lat,$lon"))
        if (grid.forecastHourlyUrl.isBlank() || grid.forecastUrl.isBlank()) {
            throw WeatherException("NWS did not return forecast URLs for $lat,$lon")
        }

        val hourly = NwsParser.parseForecastPeriods(get(grid.forecastHourlyUrl))
        val daily = NwsParser.parseForecastPeriods(get(grid.forecastUrl))
        val alerts = NwsParser.parseAlerts(get("$baseUrl/alerts/active?point=$lat,$lon"))

        val current = currentFrom(hourly)
            ?: throw WeatherException("NWS hourly forecast was empty for $lat,$lon")

        // Adopt the NWS place name if the saved location was created without one.
        val named = if (location.name.isBlank() && grid.placeName != null) {
            location.copy(name = grid.placeName!!)
        } else location

        WeatherReport(
            location = named,
            current = current,
            hourly = hourly,
            daily = daily,
            alerts = alerts,
            fetchedAt = DateUtil.now()
        )
    }

    /**
     * On-demand lookup of the nearest NWS radar station id (e.g. "KTLX") for a location — used
     * only when the user explicitly asks to see radar, so it isn't part of the regular refresh.
     * Returns null on any failure rather than throwing; the caller falls back to national radar.
     */
    suspend fun fetchRadarStation(location: WeatherLocation): String? = withContext(Dispatchers.IO) {
        try {
            val lat = String.format(Locale.US, "%.4f", location.latitude)
            val lon = String.format(Locale.US, "%.4f", location.longitude)
            NwsParser.parseGridPoint(get("$baseUrl/points/$lat,$lon")).radarStation?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Build "right now" from the leading hourly period, computing feels-like ourselves. */
    private fun currentFrom(hourly: List<ForecastPeriod>): CurrentConditions? {
        val p = hourly.firstOrNull() ?: return null
        val feelsLike = WeatherMath.feelsLikeRounded(
            p.temperatureF, p.humidityPct, p.wind.speedMph
        )
        return CurrentConditions(
            temperatureF = p.temperatureF,
            feelsLikeF = feelsLike,
            humidityPct = p.humidityPct,
            wind = p.wind,
            precipitationProbabilityPct = p.precipitationProbabilityPct,
            uvIndex = null,
            shortForecast = p.shortForecast,
            observedAt = p.startTime.ifBlank { DateUtil.now() }
        )
    }

    private fun get(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // NWS serves GeoJSON; this is the documented forecast media type.
            setRequestProperty("Accept", "application/geo+json")
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw WeatherException("NWS request failed ($code) for $urlString: ${err.take(300)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: WeatherException) {
            throw e
        } catch (e: Exception) {
            throw WeatherException("NWS request errored for $urlString", e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        // NWS policy: identify the app + a contact. Kept generic; no user data.
        const val DEFAULT_USER_AGENT = "LifeOps/1.0 (https://github.com/bvi34/lifeops)"
    }
}
