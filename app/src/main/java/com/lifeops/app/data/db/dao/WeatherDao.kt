package com.lifeops.app.data.db.dao

import androidx.room.*
import com.lifeops.app.data.db.entities.WeatherAlertEntity
import com.lifeops.app.data.db.entities.WeatherLocationEntity
import com.lifeops.app.data.db.entities.WeatherSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WeatherDao {

    // --- Locations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocation(location: WeatherLocationEntity)

    @Update
    suspend fun updateLocation(location: WeatherLocationEntity)

    @Delete
    suspend fun deleteLocation(location: WeatherLocationEntity)

    @Query("SELECT * FROM weather_locations ORDER BY sortOrder ASC, createdAt ASC")
    fun observeLocations(): Flow<List<WeatherLocationEntity>>

    @Query("SELECT * FROM weather_locations WHERE id = :id")
    fun observeLocation(id: String): Flow<WeatherLocationEntity?>

    @Query("SELECT * FROM weather_locations WHERE id = :id")
    suspend fun getLocation(id: String): WeatherLocationEntity?

    @Query("SELECT * FROM weather_locations ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllLocations(): List<WeatherLocationEntity>

    // --- Snapshots (latest reading per location) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: WeatherSnapshotEntity)

    @Query("SELECT * FROM weather_snapshots WHERE locationId = :locationId ORDER BY fetchedAt DESC LIMIT 1")
    fun observeLatestSnapshot(locationId: String): Flow<WeatherSnapshotEntity?>

    @Query("SELECT * FROM weather_snapshots WHERE locationId = :locationId ORDER BY fetchedAt DESC LIMIT 1")
    suspend fun getLatestSnapshot(locationId: String): WeatherSnapshotEntity?

    /** Prune superseded snapshots — keep only the newest [keep] rows for a location. */
    @Query(
        """
        DELETE FROM weather_snapshots
        WHERE locationId = :locationId AND id NOT IN (
            SELECT id FROM weather_snapshots
            WHERE locationId = :locationId
            ORDER BY fetchedAt DESC
            LIMIT :keep
        )
        """
    )
    suspend fun trimSnapshots(locationId: String, keep: Int)

    // --- Alerts ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlerts(alerts: List<WeatherAlertEntity>)

    @Query("SELECT * FROM weather_alerts WHERE locationId = :locationId ORDER BY expires ASC")
    fun observeAlerts(locationId: String): Flow<List<WeatherAlertEntity>>

    /** Replace-the-set semantics: an alert no longer returned by NWS is gone. */
    @Query("DELETE FROM weather_alerts WHERE locationId = :locationId")
    suspend fun clearAlerts(locationId: String)

    /** How many alerts are cached across all locations — non-zero drives a denser refresh cadence. */
    @Query("SELECT COUNT(*) FROM weather_alerts")
    suspend fun activeAlertCount(): Int

    /**
     * Drop alerts whose expiry has passed. Uses SQLite datetime() so the compare is offset-aware:
     * NWS stamps expires with a local offset ("...-05:00") while [nowIso] is UTC ("...Z"), and
     * datetime() normalizes both to UTC before comparing (a raw string compare would not).
     */
    @Query("DELETE FROM weather_alerts WHERE expires IS NOT NULL AND datetime(expires) < datetime(:nowIso)")
    suspend fun pruneExpiredAlerts(nowIso: String)

    @Transaction
    suspend fun replaceAlerts(locationId: String, alerts: List<WeatherAlertEntity>) {
        clearAlerts(locationId)
        if (alerts.isNotEmpty()) insertAlerts(alerts)
    }
}
