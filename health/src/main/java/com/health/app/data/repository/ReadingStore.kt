package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.ReadingEntity
import com.health.app.data.model.Reading
import com.health.app.data.model.ReadingType
import com.health.app.logic.TempSite
import com.health.app.logic.Temperature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Measured numbers — temperatures, weights, anything with a value and a unit.
 *
 * Filing against an open illness is [EpisodeFiling]'s judgement, not this class's: a reading knows
 * when it was taken and nothing about what was going round at the time.
 */
class ReadingStore(
    private val dao: HealthDao,
    private val episodes: EpisodeFiling
) {

    fun observeReadings(profileId: String): Flow<List<Reading>> =
        dao.observeReadings(profileId).map { rows -> rows.map { it.toModel() } }

    /**
     * The most recent reading of each kind, for the screens that ask *when was that last taken?*
     *
     * The Record tab's chronic conditions are the caller this exists for: a condition can name the
     * one measurement that matters for it — oxygen for asthma, weight for a thyroid problem — and
     * saying so is worth nothing unless the number itself is there next to it.
     *
     * Derived from the same flow the Vitals history reads rather than a query per kind: a household
     * has hundreds of readings, not millions, and one observer that re-folds beats six that each
     * re-run on every unrelated write.
     */
    fun observeLatestReadings(profileId: String): Flow<Map<ReadingType, Reading>> =
        dao.observeReadings(profileId).map { rows ->
            rows.map { it.toModel() }
                .groupBy { it.type }
                .mapValues { (_, ofType) -> ofType.maxBy { it.takenAt } }
        }

    fun observeTemperatures(profileId: String): Flow<List<Reading>> =
        dao.observeReadingsOfType(profileId, ReadingType.TEMPERATURE.key).map { rows -> rows.map { it.toModel() } }

    /**
     * Record a temperature. [celsius] is canonical — callers convert from whatever the user typed
     * via `logic/Temperature`, which also rejects impossible values before they reach here.
     */
    suspend fun logTemperature(
        profileId: String,
        celsius: Double,
        site: TempSite,
        takenAt: Long = now(),
        note: String? = null
    ): String = logReading(
        profileId = profileId,
        type = ReadingType.TEMPERATURE,
        value = celsius,
        site = site,
        takenAt = takenAt,
        note = note
    )

    /**
     * Record any measurement. [value] is canonical for its [type] — a weight in kilograms — and is
     * expected to have been checked against `logic/Vitals` by whatever typed it, the same contract
     * [logTemperature] states: bounds belong at the point of entry, where the person who typed the
     * number is still there to be told what was wrong with it.
     *
     * [takenAt] is when the measurement was *taken*. It decides which illness the row belongs to,
     * so a reading filled in afterwards lands in the story it happened in rather than today's.
     */
    suspend fun logReading(
        profileId: String,
        type: ReadingType,
        value: Double,
        secondaryValue: Double? = null,
        site: TempSite? = null,
        takenAt: Long = now(),
        note: String? = null
    ): String {
        val id = newId()
        dao.upsertReading(
            ReadingEntity(
                id = id,
                profileId = profileId,
                episodeId = episodes.episodeIdAt(profileId, takenAt),
                type = type.key,
                value = value,
                secondaryValue = secondaryValue,
                site = site?.key,
                takenAt = takenAt,
                note = note?.trim()?.ifBlank { null },
                createdAt = now()
            )
        )
        return id
    }

    /**
     * Correct a reading already recorded.
     *
     * The row keeps its id, so nothing that pointed at it is disturbed. The illness it belongs to is
     * **only** re-derived when the time moved: a reading adopted into an episode that started after
     * it was taken (see [startEpisode]'s backfill window) is filed there deliberately, and
     * recomputing that link while somebody fixes a typo in the note would quietly evict it.
     */
    suspend fun updateReading(reading: Reading) {
        val existing = dao.getReading(reading.id) ?: return
        val episodeId =
            if (reading.takenAt == existing.takenAt) existing.episodeId
            else episodes.episodeIdAt(existing.profileId, reading.takenAt)
        dao.upsertReading(
            existing.copy(
                value = reading.value,
                secondaryValue = reading.secondaryValue,
                site = reading.site?.key,
                takenAt = reading.takenAt,
                note = reading.note?.trim()?.ifBlank { null },
                episodeId = episodeId
            )
        )
    }

    /**
     * Drop a reading, and hand back the way to put it exactly where it was.
     *
     * Nobody can reconstruct what the thermometer said on Tuesday, so this is one of the deletes
     * that must be offerable back rather than merely confirmed — see [RestorableDelete].
     */
    suspend fun deleteReading(id: String): RestorableDelete? {
        val row = dao.getReading(id) ?: return null
        dao.deleteReading(id)
        return RestorableDelete { dao.upsertReading(row) }
    }
}
