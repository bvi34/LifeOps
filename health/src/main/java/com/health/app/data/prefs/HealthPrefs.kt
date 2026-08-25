package com.health.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.health.app.logic.TempUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Health's two settings: which person is on screen, and whether temperatures are shown in °C or °F.
 *
 * Neither belongs in the database. The selected profile is a UI position, not a fact about anyone's
 * health, and restoring a backup should not drag the *reader's* last screen state along with the
 * medical records. The display unit is the same kind of thing — it changes what you read, never what
 * was stored (readings are always Celsius; see `logic/Temperature`).
 *
 * The file is named `health_prefs` so the sandbox's per-app prefs isolation — every contributor
 * touches only files matching its own prefix — keeps working. See [com.health.app.backup.HealthBackupContributor].
 */
class HealthPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var selectedProfileId: String?
        get() = prefs.getString(KEY_SELECTED_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_PROFILE, value).apply()

    var temperatureUnit: TempUnit
        get() = TempUnit.fromKey(prefs.getString(KEY_TEMP_UNIT, TempUnit.CELSIUS.key)!!)
        set(value) = prefs.edit().putString(KEY_TEMP_UNIT, value.key).apply()

    /**
     * The highest version of a People-seam peer's packets Health has durably taken. Keyed by peer so
     * a fourth peer needs no new preference and no migration.
     */
    fun syncCursor(peer: String): Long = prefs.getLong("sync_cursor_$peer", 0L)

    fun setSyncCursor(peer: String, version: Long) {
        prefs.edit().putLong("sync_cursor_$peer", version).apply()
    }

    /** Emits on every change to [key], starting with the value at collection time. */
    private fun <T> observe(key: String, read: () -> T): Flow<T> = callbackFlow {
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun observeSelectedProfileId(): Flow<String?> = observe(KEY_SELECTED_PROFILE) { selectedProfileId }

    fun observeTemperatureUnit(): Flow<TempUnit> = observe(KEY_TEMP_UNIT) { temperatureUnit }

    companion object {
        const val FILE_NAME = "health_prefs"
        private const val KEY_SELECTED_PROFILE = "selected_profile_id"
        private const val KEY_TEMP_UNIT = "temperature_unit"
    }
}
