package com.health.app.data.repository

import com.google.gson.GsonBuilder
import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.ConnectRecordEntity
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.model.ConnectKindTotal
import com.health.app.data.model.ConnectRecord
import com.health.app.data.model.ImportedRecord
import com.health.app.data.model.ReadingType
import com.health.app.logic.ConnectKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What was imported from Health Connect, and the readings mirrored from it.
 *
 * Every record is kept in `connect_records`, whatever its kind. Four kinds are also **mirrored**
 * into `readings`, because Health already has a place for them and screens that read it — weight,
 * blood pressure, body temperature and resting heart rate. A mirrored reading is an ordinary
 * reading: it charts on the Vitals tab, a temperature from a smart thermometer gets a fever verdict,
 * a weight answers "when was that last taken?" beside a condition, and Advisor sees them. Its id is
 * `hc-` plus the record's, so a later import updates it rather than adding a second.
 *
 * The other vitals — oxygen, breathing rate, the heart-rate series — are **not** mirrored. A watch
 * writes those every few minutes all night, and a Vitals list of four hundred oxygen readings would
 * bury the one taken by hand during a chest infection. They are all in `connect_records`, and the
 * Health Connect screen shows them.
 *
 * Filing works as it does for typed readings: a mirrored reading taken during an open illness is
 * filed against it by [EpisodeFiling].
 */
class ConnectStore(
    private val dao: HealthDao,
    private val episodes: EpisodeFiling
) {

    /**
     * Write one batch from Health Connect under [profileId]: [upserts] replace whatever had the same
     * id, [deletions] are ids Health Connect says are gone. Both are idempotent, so a batch that is
     * delivered twice — an import interrupted before its token was saved — does no harm.
     */
    suspend fun apply(profileId: String, upserts: List<ImportedRecord>, deletions: List<String>) {
        val importedAt = now()
        upserts.chunked(CHUNK).forEach { chunk ->
            dao.upsertConnectRecords(chunk.map { it.toEntity(profileId, importedAt) })
        }
        upserts.forEach { record -> mirror(profileId, record) }
        deletions.chunked(CHUNK).forEach { chunk ->
            dao.deleteConnectRecords(chunk)
            dao.deleteReadings(chunk.map { MIRROR_PREFIX + it })
        }
    }

    /**
     * Replace everything of [kinds] held for [profileId] with [records]. For medical records, which
     * Health Connect offers no change feed for: each import reads them whole, and whatever is no
     * longer there has been deleted at the source.
     */
    suspend fun replace(profileId: String, kinds: Collection<ConnectKind>, records: List<ImportedRecord>): Int {
        val keys = kinds.map { it.key }
        val keep = records.map { it.id }.toSet()
        val gone = dao.connectRecordIdsOfKinds(profileId, keys).filterNot { it in keep }
        apply(profileId, records, gone)
        return gone.size
    }

    fun observeKindTotals(profileId: String): Flow<List<ConnectKindTotal>> =
        dao.observeConnectKindCounts(profileId).map { rows ->
            rows.mapNotNull { row ->
                ConnectKind.fromKey(row.kind)?.let { ConnectKindTotal(it, row.count, row.latestAt) }
            }.sortedBy { it.kind.ordinal }
        }

    fun observeRecords(profileId: String, kind: ConnectKind, limit: Int = 200): Flow<List<ConnectRecord>> =
        dao.observeConnectRecords(profileId, kind.key, limit).map { rows -> rows.mapNotNull { it.toModel() } }

    fun observeRecordsSince(profileId: String, since: Long): Flow<List<ConnectRecord>> =
        dao.observeConnectRecordsSince(profileId, since).map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun count(profileId: String): Int = dao.connectRecordCount(profileId)

    /** Move everything imported for [fromProfileId] onto [toProfileId]. */
    suspend fun reassign(fromProfileId: String, toProfileId: String) {
        if (fromProfileId == toProfileId) return
        dao.reassignConnectRecords(fromProfileId, toProfileId)
    }

    /** Forget every import and every reading mirrored from one. Nothing typed by hand is touched. */
    suspend fun deleteAll() {
        dao.deleteAllConnectRecords()
        dao.deleteMirroredReadings()
    }

    /**
     * Mirror [record] into `readings` if it is one of the four kinds that have a home there, and its
     * number is one Health would have accepted typed in. A number outside `logic/Vitals` is kept in
     * `connect_records` as it came, but is not put in front of the fever rules.
     */
    private suspend fun mirror(profileId: String, record: ImportedRecord) {
        val value = record.value ?: return
        val type = when (record.kind) {
            ConnectKind.WEIGHT -> ReadingType.WEIGHT
            ConnectKind.BLOOD_PRESSURE -> ReadingType.BLOOD_PRESSURE
            ConnectKind.BODY_TEMPERATURE -> ReadingType.TEMPERATURE
            ConnectKind.RESTING_HEART_RATE -> ReadingType.HEART_RATE
            else -> return
        }
        if (value !in type.range) return
        val secondary = record.secondaryValue
        if (type.secondaryRange != null && (secondary == null || secondary !in type.secondaryRange)) return
        dao.upsertReading(
            ReadingEntity(
                id = MIRROR_PREFIX + record.id,
                profileId = profileId,
                episodeId = episodes.episodeIdAt(profileId, record.startAt),
                type = type.key,
                value = value,
                secondaryValue = secondary,
                site = (record.detail["site"] as? String)?.takeIf { type == ReadingType.TEMPERATURE },
                takenAt = record.startAt,
                note = MIRROR_NOTE,
                createdAt = now()
            )
        )
    }

    private fun ImportedRecord.toEntity(profileId: String, importedAt: Long) = ConnectRecordEntity(
        id = id,
        profileId = profileId,
        kind = kind.key,
        startAt = startAt,
        endAt = endAt,
        zoneOffsetSeconds = zoneOffsetSeconds,
        value = value,
        secondaryValue = secondaryValue,
        detail = detail.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) },
        source = source,
        device = device,
        modifiedAt = modifiedAt,
        importedAt = importedAt
    )

    private fun ConnectRecordEntity.toModel(): ConnectRecord? {
        val kind = ConnectKind.fromKey(kind) ?: return null
        return ConnectRecord(id, profileId, kind, startAt, endAt, value, secondaryValue, detail, source, device)
    }

    companion object {
        /** What a mirrored reading's id starts with. Nothing typed by hand has one. */
        const val MIRROR_PREFIX = "hc-"

        /** The note a mirrored reading carries, so the Vitals list says where it came from. */
        const val MIRROR_NOTE = "From Health Connect"

        /** Under SQLite's 999-variable limit for the `IN (…)` deletes, with room to spare. */
        private const val CHUNK = 500

        /** NaN and infinity are written rather than thrown on: a bad number is still a record. */
        private val gson = GsonBuilder().serializeSpecialFloatingPointValues().create()

        fun isMirrored(readingId: String): Boolean = readingId.startsWith(MIRROR_PREFIX)
    }
}
