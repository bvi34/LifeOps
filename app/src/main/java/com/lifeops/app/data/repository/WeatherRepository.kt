package com.lifeops.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lifeops.app.data.db.dao.WeatherDao
import com.lifeops.app.data.db.entities.WeatherAlertEntity
import com.lifeops.app.data.db.entities.WeatherLocationEntity
import com.lifeops.app.data.db.entities.WeatherSnapshotEntity
import com.lifeops.app.data.model.CurrentConditions
import com.lifeops.app.data.model.ForecastPeriod
import com.lifeops.app.data.model.WeatherLocation
import com.lifeops.app.data.model.WeatherReport
import com.lifeops.app.data.model.Wind
import com.lifeops.app.data.weather.NwsClient
import com.lifeops.app.util.DateUtil
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The one thing LifeOps talks to for weather. Cache-first by design: [observeReport] streams a
 * fully-assembled [WeatherReport] straight from the local Room cache and never blocks on the
 * network, so opening the app is instant. [refresh] is the only path that reaches out to NWS
 * (via [NwsClient]); it swallows failures into a Result so a dropped connection just means the
 * cached report keeps showing, never a crash.
 */
class WeatherRepository(
    private val weatherDao: WeatherDao,
    private val client: NwsClient = NwsClient()
) {
    private val gson = Gson()
    private val periodListType = object : TypeToken<List<ForecastPeriod>>() {}.type

    // --- Locations ---

    fun observeLocations(): Flow<List<WeatherLocation>> =
        weatherDao.observeLocations().map { list -> list.map { it.toModel() } }

    fun observeLocation(id: String): Flow<WeatherLocation?> =
        weatherDao.observeLocation(id).map { it?.toModel() }

    suspend fun getLocation(id: String): WeatherLocation? =
        weatherDao.getLocation(id)?.toModel()

    /** Create a tracked location. NWS fills in a blank [name] on the first refresh. */
    suspend fun addLocation(
        latitude: Double,
        longitude: Double,
        name: String = "",
        sortOrder: Int = 0
    ): WeatherLocation {
        val entity = WeatherLocationEntity(
            id = UUID.randomUUID().toString(),
            latitude = latitude,
            longitude = longitude,
            name = name,
            sortOrder = sortOrder,
            createdAt = DateUtil.now()
        )
        weatherDao.upsertLocation(entity)
        return entity.toModel()
    }

    suspend fun renameLocation(id: String, name: String) {
        val existing = weatherDao.getLocation(id) ?: return
        weatherDao.updateLocation(existing.copy(name = name))
    }

    suspend fun deleteLocation(id: String) {
        val existing = weatherDao.getLocation(id) ?: return
        weatherDao.deleteLocation(existing) // snapshots + alerts cascade
    }

    // --- Cached report (offline-first read path) ---

    /**
     * Stream the current [WeatherReport] for [locationId] entirely from cache — location,
     * latest snapshot, and active alerts recombined. Emits null until the first successful
     * refresh has populated a snapshot. Never touches the network.
     */
    fun observeReport(locationId: String): Flow<WeatherReport?> =
        combine(
            weatherDao.observeLocation(locationId),
            weatherDao.observeLatestSnapshot(locationId),
            weatherDao.observeAlerts(locationId)
        ) { location, snapshot, alerts ->
            if (location == null || snapshot == null) return@combine null
            assembleReport(location, snapshot, alerts.map { it.toModel() })
        }

    suspend fun getCachedReport(locationId: String): WeatherReport? {
        val location = weatherDao.getLocation(locationId) ?: return null
        val snapshot = weatherDao.getLatestSnapshot(locationId) ?: return null
        // Snapshot columns already carry the current conditions; alerts come from the flow path
        // when observed. For the one-shot read we reconstruct without alerts on the snapshot.
        return assembleReport(location, snapshot, emptyList())
    }

    private fun assembleReport(
        location: WeatherLocationEntity,
        snapshot: WeatherSnapshotEntity,
        alerts: List<com.lifeops.app.data.model.WeatherAlert>
    ): WeatherReport {
        val hourly: List<ForecastPeriod> =
            runCatching { gson.fromJson<List<ForecastPeriod>>(snapshot.hourlyJson, periodListType) }
                .getOrNull() ?: emptyList()
        val daily: List<ForecastPeriod> =
            runCatching { gson.fromJson<List<ForecastPeriod>>(snapshot.dailyJson, periodListType) }
                .getOrNull() ?: emptyList()
        val current = CurrentConditions(
            temperatureF = snapshot.temperatureF,
            feelsLikeF = snapshot.feelsLikeF,
            humidityPct = snapshot.humidityPct,
            wind = Wind(snapshot.windSpeedMph, snapshot.windDirection, snapshot.windGustMph),
            precipitationProbabilityPct = snapshot.precipitationProbabilityPct,
            uvIndex = snapshot.uvIndex,
            shortForecast = snapshot.shortForecast,
            observedAt = snapshot.observedAt
        )
        return WeatherReport(
            location = location.toModel(),
            current = current,
            hourly = hourly,
            daily = daily,
            alerts = alerts,
            fetchedAt = snapshot.fetchedAt
        )
    }

    // --- Refresh (the only network path) ---

    /**
     * Fetch fresh data for [locationId] from NWS and write it into the cache. Returns the new
     * [WeatherReport] on success, or a failure carrying the cause — callers keep serving whatever
     * was already cached. Also adopts the NWS-resolved place name for unnamed locations, trims
     * superseded snapshots, and replaces the alert set (dropping ones NWS no longer reports).
     */
    suspend fun refresh(locationId: String): Result<WeatherReport> {
        val locationEntity = weatherDao.getLocation(locationId)
            ?: return Result.failure(IllegalArgumentException("No weather location $locationId"))

        return try {
            val report = client.fetchReport(locationEntity.toModel())

            // Persist a name NWS resolved for a previously-blank location.
            if (locationEntity.name.isBlank() && report.location.name.isNotBlank()) {
                weatherDao.updateLocation(locationEntity.copy(name = report.location.name))
            }

            weatherDao.insertSnapshot(snapshotOf(locationId, report))
            weatherDao.trimSnapshots(locationId, SNAPSHOTS_KEPT)
            weatherDao.replaceAlerts(locationId, report.alerts.map { alertEntityOf(locationId, it, report.fetchedAt) })
            weatherDao.pruneExpiredAlerts(DateUtil.now())

            Result.success(report)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Refresh every tracked location; returns how many succeeded. */
    suspend fun refreshAll(): Int =
        weatherDao.getAllLocations().count { refresh(it.id).isSuccess }

    suspend fun pruneExpiredAlerts() = weatherDao.pruneExpiredAlerts(DateUtil.now())

    /** True when any (non-expired) alert is cached — the refresh worker uses this to tighten
     *  its cadence while severe weather is in play. Call after [pruneExpiredAlerts]. */
    suspend fun hasActiveAlerts(): Boolean = weatherDao.activeAlertCount() > 0

    private fun snapshotOf(locationId: String, report: WeatherReport) = WeatherSnapshotEntity(
        id = UUID.randomUUID().toString(),
        locationId = locationId,
        fetchedAt = report.fetchedAt,
        observedAt = report.current.observedAt,
        temperatureF = report.current.temperatureF,
        feelsLikeF = report.current.feelsLikeF,
        humidityPct = report.current.humidityPct,
        windSpeedMph = report.current.wind.speedMph,
        windDirection = report.current.wind.directionCardinal,
        windGustMph = report.current.wind.gustMph,
        uvIndex = report.current.uvIndex,
        precipitationProbabilityPct = report.current.precipitationProbabilityPct,
        shortForecast = report.current.shortForecast,
        hourlyJson = gson.toJson(report.hourly),
        dailyJson = gson.toJson(report.daily)
    )

    private fun alertEntityOf(
        locationId: String,
        alert: com.lifeops.app.data.model.WeatherAlert,
        fetchedAt: String
    ) = WeatherAlertEntity(
        id = alert.id,
        locationId = locationId,
        event = alert.event,
        severity = alert.severity.value,
        headline = alert.headline,
        description = alert.description,
        instruction = alert.instruction,
        onset = alert.onset,
        expires = alert.expires,
        areaDesc = alert.areaDesc,
        fetchedAt = fetchedAt
    )

    companion object {
        /** Retain a short snapshot history per location; the newest is what the UI reads. */
        const val SNAPSHOTS_KEPT = 8
    }
}
