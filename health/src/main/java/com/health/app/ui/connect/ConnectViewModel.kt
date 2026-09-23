package com.health.app.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.health.app.HealthApp
import com.health.app.connect.ConnectTypes
import com.health.app.connect.HealthConnectImporter
import com.health.app.data.model.ConnectKindTotal
import com.health.app.data.model.ConnectRecord
import com.health.app.data.model.Profile
import com.health.app.logic.ConnectKind
import com.health.app.logic.ConnectPoint
import com.health.app.logic.ConnectSummaries
import com.health.app.logic.DayFigure
import com.health.app.logic.TempUnit
import com.health.app.logic.WeightUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Health Connect screen's state: whose data this phone's Health Connect holds, whether the
 * import is on and allowed, and what it has brought in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModel(private val app: HealthApp) : ViewModel() {

    private val repo = app.repository
    private val prefs = app.prefs
    private val importer = app.connectImporter

    val availability: HealthConnectImporter.Availability = importer.availability()

    val profiles: StateFlow<List<Profile>> =
        repo.profiles.observeProfiles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The primary user, or null when none is chosen or the chosen one has left the household. */
    val primary: StateFlow<Profile?> =
        combine(profiles, prefs.observePrimaryProfileId()) { all, id -> all.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val enabled: StateFlow<Boolean> =
        prefs.observeConnectEnabled().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefs.connectEnabled)

    val lastSyncAt: StateFlow<Long> =
        prefs.observeConnectLastSyncAt().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val lastResult: StateFlow<String?> =
        prefs.observeConnectLastResult().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tempUnit: StateFlow<TempUnit> =
        repo.profiles.observeTemperatureUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TempUnit.CELSIUS)

    val weightUnit: StateFlow<WeightUnit> =
        repo.profiles.observeWeightUnit().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.KILOGRAMS)

    private val _granted = MutableStateFlow<Set<String>>(emptySet())

    /** What Health Connect currently allows Health to read. */
    val granted: StateFlow<Set<String>> = _granted.asStateFlow()

    /** What is worth asking for on this phone — see [HealthConnectImporter.requestablePermissions]. */
    val requestable: Set<String> by lazy { runCatching { importer.requestablePermissions() }.getOrDefault(emptySet()) }

    fun permissionContract() = importer.permissionContract()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** How much of each kind has been imported for the primary user. */
    val totals: StateFlow<List<ConnectKindTotal>> = primary
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else repo.connect.observeKindTotals(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today, for the primary user: summed kinds added up, the rest as their latest reading. */
    val today: StateFlow<List<DayFigure>> = primary
        .flatMapLatest { p ->
            if (p == null) {
                flowOf(emptyList())
            } else {
                val zone = ZoneId.systemDefault()
                val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                repo.connect.observeRecordsSince(p.id, start).map { rows ->
                    ConnectSummaries.day(rows.filterNot { it.kind.isMedical }.map { it.toPoint() }, start, end)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshGranted()
    }

    /** Re-read the grants — after the permission screen, and whenever this screen comes back. */
    fun refreshGranted() = viewModelScope.launch {
        _granted.value = runCatching { importer.grantedPermissions() }.getOrDefault(emptySet())
    }

    /** How many imported records the current primary user has — what a change of primary moves. */
    suspend fun importedCountFor(profileId: String): Int = repo.connect.count(profileId)

    /**
     * Make [profile] the primary user. When somebody else was, [moveExisting] says whether what was
     * imported under them moves too: yes when the old choice was a mistake, no when the phone has
     * genuinely changed hands and the old records really are the old owner's.
     */
    fun setPrimary(profile: Profile, moveExisting: Boolean) = viewModelScope.launch {
        val previous = prefs.primaryProfileId
        if (moveExisting && previous != null && previous != profile.id) {
            repo.connect.reassign(previous, profile.id)
        }
        prefs.primaryProfileId = profile.id
    }

    /** Switch the import on, and import straight away with whatever is already allowed. */
    fun turnOn() {
        app.setConnectEnabled(true)
        importNow()
    }

    fun turnOff() = app.setConnectEnabled(false)

    /** What the permission screen said. Anything newly allowed is imported at once. */
    fun onPermissionsResult() {
        refreshGranted()
        if (prefs.connectEnabled) importNow()
    }

    fun importNow() {
        if (_importing.value) return
        viewModelScope.launch {
            _importing.value = true
            runCatching { importer.sync(inForeground = true) }
            _importing.value = false
        }
    }

    /** Withdraw every grant in Health Connect and stop importing. Imported records stay. */
    fun disconnect() = viewModelScope.launch {
        runCatching { importer.revokeAll() }
        app.setConnectEnabled(false)
        refreshGranted()
    }

    /** Forget everything imported, for everyone. The next import reads it all again. */
    fun deleteImported() = viewModelScope.launch {
        repo.connect.deleteAll()
        prefs.resetConnectToken()
    }

    fun records(kind: ConnectKind): StateFlow<List<ConnectRecord>> = primary
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else repo.connect.observeRecords(p.id, kind) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many of the kinds Health imports are allowed, counting a shared permission once. */
    fun allowedKinds(granted: Set<String>): Int =
        ConnectKind.entries.count { ConnectTypes.permissionFor(it) in granted }

    fun offeredKinds(): Int =
        ConnectKind.entries.count { ConnectTypes.permissionFor(it) in requestable }

    private fun ConnectRecord.toPoint() = ConnectPoint(kind, startAt, endAt, value, secondaryValue, source)

    class Factory(private val app: HealthApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel(app) as T
    }
}
