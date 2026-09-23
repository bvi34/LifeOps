package com.health.app.connect

import android.content.Context
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.GetMedicalDataSourcesRequest
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadMedicalResourcesPageRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.gson.Gson
import com.health.app.data.model.ImportedRecord
import com.health.app.data.prefs.HealthPrefs
import com.health.app.data.repository.ConnectStore
import com.health.app.logic.ConnectKind
import com.health.app.logic.FhirSummaries
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Reads what Health Connect holds into Health, under the primary user.
 *
 * ## What an import does
 *
 * The first import reads everything Health Connect will give: all of history when the history
 * permission is granted, and otherwise the thirty days Health Connect allows without it. Every
 * import after that asks Health Connect only for what changed since the last one — records added,
 * edited or deleted — using a changes token. A token is taken **before** the full read, so anything
 * written while the read runs is in the next import's changes rather than lost between the two;
 * the overlap is harmless, because every write here is keyed by Health Connect's own record id.
 *
 * Medical records have no changes feed, so each import reads them whole and replaces what was there.
 *
 * ## Who it is filed under
 *
 * Everything goes under [HealthPrefs.primaryProfileId]. No primary user, no import: Health keeps
 * records for a household and Health Connect's are one person's, so the import waits until
 * somebody says whose they are.
 *
 * ## Only when asked
 *
 * Nothing is read until the household switches the import on ([HealthPrefs.connectEnabled]) and
 * grants permissions in Health Connect's own screen. It reads, and never writes: Health adds nothing
 * to Health Connect and changes nothing there. What is read stays on the phone, in `health.db`, and
 * travels only where the rest of Health's records do — the suite's own backup.
 */
@OptIn(ExperimentalPersonalHealthRecordApi::class, ExperimentalMindfulnessSessionApi::class)
class HealthConnectImporter(
    context: Context,
    private val prefs: HealthPrefs,
    private val store: ConnectStore,
    /** Whether a profile still exists — a primary user who has since been deleted is nobody. */
    private val profileExists: suspend (String) -> Boolean
) {

    private val appContext = context.applicationContext
    private val lock = Mutex()
    private val gson = Gson()

    /** Whether Health Connect can be used on this phone at all. */
    enum class Availability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(appContext)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_UPDATE
        else -> Availability.UNAVAILABLE
    }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    /** The contract the screen launches to ask for permissions in Health Connect's own dialog. */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /**
     * The permissions worth asking for on this phone: every kind whose feature Health Connect has
     * here, plus history and background reading where it offers them. Asking for a data type the
     * platform doesn't have yet — skin temperature on an early Android 14 build — only clutters the
     * dialog.
     */
    fun requestablePermissions(): Set<String> {
        if (availability() != Availability.AVAILABLE) return emptySet()
        val missing = FEATURE_GATED.filterValues { !has(it) }.keys
        return ConnectKind.entries
            .filterNot { it in missing }
            .map { ConnectTypes.permissionFor(it) }
            .toSet() +
            listOfNotNull(
                ConnectTypes.HISTORY.takeIf { has(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) },
                ConnectTypes.BACKGROUND.takeIf { has(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) }
            )
    }

    suspend fun grantedPermissions(): Set<String> =
        if (availability() == Availability.AVAILABLE) client.permissionController.getGrantedPermissions() else emptySet()

    /** Withdraw every grant, in Health Connect itself. What was already imported stays until deleted. */
    suspend fun revokeAll() {
        if (availability() == Availability.AVAILABLE) client.permissionController.revokeAllPermissions()
        prefs.resetConnectToken()
    }

    /** How an import ended. [message] is the line the Health Connect screen shows for it. */
    sealed class Outcome(val message: String) {
        class Imported(val upserted: Int, val deleted: Int) : Outcome(
            when {
                upserted == 0 && deleted == 0 -> "Up to date"
                deleted == 0 -> "Imported ${"%,d".format(upserted)} records"
                else -> "Imported ${"%,d".format(upserted)} records, removed ${"%,d".format(deleted)}"
            }
        )
        data object NotEnabled : Outcome("Import is off")
        data object NoPrimaryUser : Outcome("Choose whose data this is before importing")
        data object Unavailable : Outcome("Health Connect isn't available on this phone")
        data object NoPermissions : Outcome("No data types allowed in Health Connect yet")
        data object NoBackgroundAccess : Outcome("Waiting to be opened — background reading isn't allowed")
        class Failed(detail: String) : Outcome("Import failed: $detail")
    }

    /**
     * Run one import. [inForeground] is whether Health is on screen: Health Connect refuses reads from
     * the background unless that permission was granted, so a background run without it stops here
     * instead of failing.
     *
     * Serialised: a foreground import and the scheduled one never interleave their tokens.
     */
    suspend fun sync(inForeground: Boolean): Outcome = lock.withLock {
        val outcome = runCatching { syncLocked(inForeground) }
            .getOrElse { failure ->
                Log.w(TAG, "Health Connect import failed", failure)
                Outcome.Failed(failure.message ?: failure.javaClass.simpleName)
            }
        if (outcome is Outcome.Imported || outcome is Outcome.Failed) {
            prefs.connectLastSyncAt = System.currentTimeMillis()
        }
        prefs.connectLastResult = outcome.message
        outcome
    }

    private suspend fun syncLocked(inForeground: Boolean): Outcome {
        if (!prefs.connectEnabled) return Outcome.NotEnabled
        val profileId = prefs.primaryProfileId?.takeIf { profileExists(it) } ?: return Outcome.NoPrimaryUser
        if (availability() != Availability.AVAILABLE) return Outcome.Unavailable

        val granted = client.permissionController.getGrantedPermissions()
        if (!inForeground && ConnectTypes.BACKGROUND !in granted) return Outcome.NoBackgroundAccess

        val kinds = ConnectTypes.RECORDS.keys.filter { ConnectTypes.permissionFor(it) in granted }.toSet()
        val medical = ConnectTypes.MEDICAL.keys.filter { ConnectTypes.permissionFor(it) in granted }.toSet()
        if (kinds.isEmpty() && medical.isEmpty()) return Outcome.NoPermissions

        val tally = Tally()
        if (kinds.isNotEmpty()) importRecords(profileId, kinds, tally)
        if (medical.isNotEmpty()) importMedical(profileId, medical, tally)
        return Outcome.Imported(tally.upserted, tally.deleted)
    }

    private class Tally {
        var upserted = 0
        var deleted = 0
    }

    /**
     * The ordinary record types: drain the changes feed if the token covers exactly what is granted
     * now, otherwise start a new token and read what the old one didn't cover.
     */
    private suspend fun importRecords(profileId: String, kinds: Set<ConnectKind>, tally: Tally) {
        val keys = kinds.map { it.key }.toSet()
        val token = prefs.connectChangesToken
        val covered = prefs.connectTokenKinds

        if (token != null && covered == keys) {
            if (drain(profileId, token, tally)) return
            // Expired or refused: start over. Re-reading is safe; every write is keyed by id.
            readFresh(profileId, kinds, kinds, tally)
            return
        }

        // The grants changed since the token was issued (or there never was one). Catch up on what
        // the old token covered first, if it still can — it only fails when a grant was withdrawn.
        val stillCovered = kinds.filter { it.key in covered }.toSet()
        val caughtUp = token != null && covered.all { it in keys } && drain(profileId, token, tally)
        val toRead = if (caughtUp) kinds - stillCovered else kinds
        readFresh(profileId, kinds, toRead, tally)
    }

    /**
     * Take a token for all of [kinds], then read [toRead] in full. The token comes first so nothing
     * written during the read slips between the two.
     */
    private suspend fun readFresh(profileId: String, kinds: Set<ConnectKind>, toRead: Set<ConnectKind>, tally: Tally) {
        val types: Set<KClass<out Record>> = kinds.map { ConnectTypes.RECORDS.getValue(it) }.toSet()
        val fresh = client.getChangesToken(ChangesTokenRequest(recordTypes = types))
        for (kind in toRead) readAll(profileId, ConnectTypes.RECORDS.getValue(kind), tally)
        prefs.connectChangesToken = fresh
        prefs.connectTokenKinds = kinds.map { it.key }.toSet()
    }

    /** Every record of [type] Health Connect will hand over, a page at a time. */
    private suspend fun <T : Record> readAll(profileId: String, type: KClass<T>, tally: Tally) {
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    // From the beginning: Health Connect itself limits this to thirty days unless
                    // the history permission is granted.
                    timeRangeFilter = TimeRangeFilter.after(Instant.EPOCH),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken
                )
            )
            val mapped = response.records.mapNotNull { RecordMapper.map(it) }
            store.apply(profileId, mapped, emptyList())
            tally.upserted += mapped.size
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
    }

    /**
     * Follow the changes feed from [start] to its end, saving the token after every page so an
     * import cut off halfway resumes where it stopped. False if the token is no good any more.
     */
    private suspend fun drain(profileId: String, start: String, tally: Tally): Boolean {
        var token = start
        while (true) {
            val response = runCatching { client.getChanges(token) }.getOrElse { failure ->
                Log.i(TAG, "Changes token refused; reading afresh", failure)
                prefs.resetConnectToken()
                return false
            }
            if (response.changesTokenExpired) {
                prefs.resetConnectToken()
                return false
            }
            val upserts = response.changes.filterIsInstance<UpsertionChange>().mapNotNull { RecordMapper.map(it.record) }
            val deletions = response.changes.filterIsInstance<DeletionChange>().map { it.recordId }
            store.apply(profileId, upserts, deletions)
            tally.upserted += upserts.size
            tally.deleted += deletions.size
            token = response.nextChangesToken
            prefs.connectChangesToken = token
            if (!response.hasMore) return true
        }
    }

    /** Medical records, read whole per granted type and swapped in for what was there. */
    private suspend fun importMedical(profileId: String, kinds: Set<ConnectKind>, tally: Tally) {
        if (!has(HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD)) return
        val sources = runCatching {
            client.getMedicalDataSources(GetMedicalDataSourcesRequest(packageNames = emptyList()))
        }.getOrDefault(emptyList()).associateBy { it.id }
        val now = System.currentTimeMillis()

        for (kind in kinds) {
            val type = ConnectTypes.MEDICAL.getValue(kind).resourceType
            val resources = mutableListOf<MedicalResource>()
            var response = client.readMedicalResources(ReadMedicalResourcesInitialRequest(type, emptySet(), PAGE_SIZE))
            resources += response.medicalResources
            while (!response.nextPageToken.isNullOrEmpty()) {
                response = client.readMedicalResources(ReadMedicalResourcesPageRequest(response.nextPageToken!!, PAGE_SIZE))
                resources += response.medicalResources
            }
            val records = resources.map { resource ->
                val json = resource.fhirResource.data
                val summary = FhirSummaries.of(json)
                val source = sources[resource.dataSourceId]
                ImportedRecord(
                    id = "medical:${resource.dataSourceId}:${resource.fhirResource.type}:${resource.fhirResource.id}",
                    kind = kind,
                    startAt = summary.date ?: now,
                    endAt = null,
                    zoneOffsetSeconds = null,
                    value = null,
                    detail = mapOf(
                        "title" to summary.title,
                        "resourceType" to summary.resourceType,
                        "fhirVersion" to resource.fhirVersion.let { "${it.major}.${it.minor}.${it.patch}" },
                        "resource" to runCatching { gson.fromJson(json, Map::class.java) }.getOrNull()
                    ).filterValues { it != null },
                    source = source?.packageName,
                    device = source?.displayName,
                    modifiedAt = source?.lastDataUpdateTime?.toEpochMilli() ?: now
                )
            }
            tally.deleted += store.replace(profileId, listOf(kind), records)
            tally.upserted += records.size
        }
    }

    private fun has(feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private companion object {
        const val TAG = "HealthConnectImport"
        const val PAGE_SIZE = 1000

        /** Kinds that exist only where Health Connect has the feature that introduced them. */
            val FEATURE_GATED: Map<ConnectKind, Int> =
            mapOf(
                ConnectKind.SKIN_TEMPERATURE to HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE,
                ConnectKind.PLANNED_EXERCISE to HealthConnectFeatures.FEATURE_PLANNED_EXERCISE,
                ConnectKind.MINDFULNESS to HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION
            ) + ConnectKind.entries.filter { it.isMedical }
                .associateWith { HealthConnectFeatures.FEATURE_PERSONAL_HEALTH_RECORD }
    }
}
