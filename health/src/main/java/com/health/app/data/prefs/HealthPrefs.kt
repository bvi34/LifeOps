package com.health.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.health.app.logic.TempUnit
import com.health.app.logic.WeightUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Health's settings: which person is on screen, the units its numbers are read in — °C or °F for
 * temperatures, kg or lb for weights — and whose this phone's Health Connect data is, with where
 * the import has got to.
 *
 * None of them belongs in the database. The selected profile is a UI position, not a fact about
 * anyone's health, and restoring a backup should not drag the *reader's* last screen state along
 * with the medical records. The display units are the same kind of thing — they change what you
 * read, never what was stored (temperatures are always Celsius and weights always kilograms; see
 * `logic/Temperature` and `logic/Weight`).
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

    var weightUnit: WeightUnit
        get() = WeightUnit.fromKey(prefs.getString(KEY_WEIGHT_UNIT, WeightUnit.KILOGRAMS.key)!!)
        set(value) = prefs.edit().putString(KEY_WEIGHT_UNIT, value.key).apply()

    /**
     * The household member this phone's Health Connect data belongs to — whose steps, sleep and
     * heart rate the import files things under. Null until somebody says.
     *
     * Health keeps records for a whole household, and Health Connect holds one person's: the
     * phone's. Nothing is imported until this is set, because guessing — "whoever is selected",
     * "the first profile" — is how a parent's resting heart rate ends up on a child's chart.
     *
     * A preference rather than a column: it is a fact about *this phone*, not about the person, and
     * the same household on a second phone has a different answer.
     */
    var primaryProfileId: String?
        get() = prefs.getString(KEY_PRIMARY_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_PRIMARY_PROFILE, value).apply()

    fun observePrimaryProfileId(): Flow<String?> = observe(KEY_PRIMARY_PROFILE) { primaryProfileId }

    /**
     * Whether the household has switched the Health Connect import on. Separate from the
     * permissions, which Health Connect keeps: they can be granted while the import is paused, and
     * Health never reads with a grant it was not asked to use.
     */
    var connectEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONNECT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONNECT_ENABLED, value).apply()

    fun observeConnectEnabled(): Flow<Boolean> = observe(KEY_CONNECT_ENABLED) { connectEnabled }

    /**
     * Health Connect's changes token, and the kinds it was issued for.
     *
     * A token is only good on the phone that issued it, and this file travels in the backup. That is
     * harmless: a token Health Connect refuses is treated like an expired one, and either is met
     * with a fresh read of everything, which the import de-duplicates by id.
     */
    var connectChangesToken: String?
        get() = prefs.getString(KEY_CONNECT_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_CONNECT_TOKEN, value).apply()

    var connectTokenKinds: Set<String>
        get() = prefs.getStringSet(KEY_CONNECT_TOKEN_KINDS, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_CONNECT_TOKEN_KINDS, value).apply()

    /** When the last import finished, and what it said — for the Health Connect screen's status. */
    var connectLastSyncAt: Long
        get() = prefs.getLong(KEY_CONNECT_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_CONNECT_LAST_SYNC, value).apply()

    var connectLastResult: String?
        get() = prefs.getString(KEY_CONNECT_LAST_RESULT, null)
        set(value) = prefs.edit().putString(KEY_CONNECT_LAST_RESULT, value).apply()

    fun observeConnectLastSyncAt(): Flow<Long> = observe(KEY_CONNECT_LAST_SYNC) { connectLastSyncAt }

    fun observeConnectLastResult(): Flow<String?> = observe(KEY_CONNECT_LAST_RESULT) { connectLastResult }

    /** Forget the token, so the next import reads everything again. */
    fun resetConnectToken() {
        prefs.edit().remove(KEY_CONNECT_TOKEN).remove(KEY_CONNECT_TOKEN_KINDS).apply()
    }

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

    fun observeWeightUnit(): Flow<WeightUnit> = observe(KEY_WEIGHT_UNIT) { weightUnit }

    companion object {
        const val FILE_NAME = "health_prefs"
        private const val KEY_SELECTED_PROFILE = "selected_profile_id"
        private const val KEY_TEMP_UNIT = "temperature_unit"
        private const val KEY_WEIGHT_UNIT = "weight_unit"
        private const val KEY_PRIMARY_PROFILE = "primary_profile_id"
        private const val KEY_CONNECT_ENABLED = "connect_enabled"
        private const val KEY_CONNECT_TOKEN = "connect_changes_token"
        private const val KEY_CONNECT_TOKEN_KINDS = "connect_token_kinds"
        private const val KEY_CONNECT_LAST_SYNC = "connect_last_sync_at"
        private const val KEY_CONNECT_LAST_RESULT = "connect_last_result"
    }
}
