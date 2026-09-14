package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.EpisodeEntity
import com.health.app.data.model.Episode
import com.health.app.data.model.ReadingType
import com.health.app.logic.DosePoint
import com.health.app.logic.EpisodeFacts
import com.health.app.logic.EpisodeSummaries
import com.health.app.logic.EpisodeSummary
import com.health.app.logic.SymptomPoint
import com.health.app.logic.TempPoint
import com.health.app.logic.TempSite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Illnesses: when one started, what happened during it, and when it was over.

 * The class that makes "how long has this been going on" have a single answer. Deciding which
 * episode a *new* record belongs to is [EpisodeFiling]'s narrower job; re-filing records when an
 * episode's own dates move is this one's.
 */
class EpisodeStore(
    private val dao: HealthDao
) {

    fun observeEpisodes(profileId: String): Flow<List<Episode>> =
        dao.observeEpisodes(profileId).map { rows -> rows.map { it.toModel() } }

    fun observeOpenEpisode(profileId: String): Flow<Episode?> =
        dao.observeOpenEpisode(profileId).map { it?.toModel() }

    /**
     * Start an illness. Any episode still open for this person is closed first — one open episode
     * per person, so "how long has this been going on" has exactly one answer.
     *
     * Readings, symptoms and doses already recorded in the [backfillWindowMillis] before it started
     * are adopted into it: an illness is nearly always noticed after the first temperature was taken,
     * and making someone re-enter that reading is how records stop being kept.
     */
    suspend fun startEpisode(
        profileId: String,
        title: String,
        startedAt: Long = now(),
        note: String? = null,
        backfillWindowMillis: Long = DEFAULT_BACKFILL_MS
    ): String {
        dao.getOpenEpisode(profileId)?.let { open ->
            dao.upsertEpisode(open.copy(endedAt = startedAt, updatedAt = now()))
        }
        val id = newId()
        val timestamp = now()
        dao.upsertEpisode(
            EpisodeEntity(
                id = id,
                profileId = profileId,
                title = title.trim().ifBlank { "Illness" },
                startedAt = startedAt,
                endedAt = null,
                note = note?.trim()?.ifBlank { null },
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        adoptRecentRecords(profileId, id, since = startedAt - backfillWindowMillis)
        return id
    }

    /**
     * File this person's unattached records in a span against an episode.
     *
     * Only records with no episode of their own — anything already filed under another illness stays
     * where it is. That is what keeps this safe to run when an episode's dates change: widening a
     * span cannot steal last month's flu's records into this month's cold.
     *
     * [until] matters once episodes can be backdated. Adopting everything after a start date would,
     * for an illness entered a fortnight late, sweep up two weeks of unrelated records that happened
     * after it was over.
     */
    private suspend fun adoptRecentRecords(
        profileId: String,
        episodeId: String,
        since: Long,
        until: Long = Long.MAX_VALUE
    ) {
        dao.getAllReadings()
            .filter { it.profileId == profileId && it.episodeId == null && it.takenAt in since..until }
            .forEach { dao.upsertReading(it.copy(episodeId = episodeId)) }
        dao.getAllSymptoms()
            .filter { it.profileId == profileId && it.episodeId == null && it.startedAt in since..until }
            .forEach { dao.upsertSymptom(it.copy(episodeId = episodeId)) }
        dao.getAllDoses()
            .filter { it.profileId == profileId && it.episodeId == null && it.takenAt in since..until }
            .forEach { dao.upsertDose(it.copy(episodeId = episodeId)) }
        dao.getAllCareNotes()
            .filter { it.profileId == profileId && it.episodeId == null && it.at in since..until }
            .forEach { dao.upsertCareNote(it.copy(episodeId = episodeId)) }
    }

    /**
     * Move an illness's dates — which is how one that was never recorded at the time gets entered at
     * all. "We had the flu the first week of March" is an episode with both ends in the past, and
     * without this there is no way to say so.
     *
     * Re-files as it goes, in both directions, because the span *is* what decides membership:
     * unattached records that now fall inside are adopted, and records filed under this episode that
     * now fall outside are released rather than left claiming to have happened during an illness
     * they no longer overlap. Records belonging to another illness are never touched.
     */
    suspend fun setEpisodeDates(episodeId: String, startedAt: Long, endedAt: Long?) {
        val episode = dao.getEpisode(episodeId) ?: return
        dao.upsertEpisode(episode.copy(startedAt = startedAt, endedAt = endedAt, updatedAt = now()))

        val until = endedAt ?: Long.MAX_VALUE
        releaseRecordsOutside(episodeId, startedAt, until)
        adoptRecentRecords(episode.profileId, episodeId, since = startedAt, until = until)
    }

    /** Detach this episode's records that no longer fall inside its span. */
    private suspend fun releaseRecordsOutside(episodeId: String, from: Long, until: Long) {
        dao.getReadingsForEpisode(episodeId)
            .filterNot { it.takenAt in from..until }
            .forEach { dao.upsertReading(it.copy(episodeId = null)) }
        dao.getSymptomsForEpisode(episodeId)
            .filterNot { it.startedAt in from..until }
            .forEach { dao.upsertSymptom(it.copy(episodeId = null)) }
        dao.getDosesForEpisode(episodeId)
            .filterNot { it.takenAt in from..until }
            .forEach { dao.upsertDose(it.copy(episodeId = null)) }
        dao.getCareNotesForEpisode(episodeId)
            .filterNot { it.at in from..until }
            .forEach { dao.upsertCareNote(it.copy(episodeId = null)) }
    }

    /** Close an episode, and resolve anything still marked active inside it. */
    suspend fun endEpisode(episodeId: String, endedAt: Long = now()) {
        val episode = dao.getEpisode(episodeId) ?: return
        dao.upsertEpisode(episode.copy(endedAt = endedAt, updatedAt = now()))
        dao.getSymptomsForEpisode(episodeId)
            .filter { it.endedAt == null }
            .forEach { dao.upsertSymptom(it.copy(endedAt = endedAt)) }
    }

    suspend fun reopenEpisode(episodeId: String) {
        val episode = dao.getEpisode(episodeId) ?: return
        dao.getOpenEpisode(episode.profileId)
            ?.takeIf { it.id != episodeId }
            ?.let { dao.upsertEpisode(it.copy(endedAt = now(), updatedAt = now())) }
        dao.upsertEpisode(episode.copy(endedAt = null, updatedAt = now()))
    }

    /** Delete the episode, keeping every reading, symptom and dose that was filed under it. */
    suspend fun deleteEpisode(episodeId: String) = dao.deleteEpisodeKeepingRecords(episodeId)

    /** Read one episode back as the summary in `logic/EpisodeSummary` — the whole story, assessed. */
    suspend fun summarizeEpisode(episodeId: String, nowMillis: Long = now()): EpisodeSummary? {
        val episode = dao.getEpisode(episodeId) ?: return null
        val profile = dao.getProfile(episode.profileId)
        return EpisodeSummaries.summarize(
            EpisodeFacts(
                title = episode.title,
                startedAtMillis = episode.startedAt,
                endedAtMillis = episode.endedAt,
                ageMonths = profile?.toModel()?.ageMonthsAt(nowMillis),
                temps = dao.getReadingsForEpisode(episodeId)
                    .filter { it.type == ReadingType.TEMPERATURE.key }
                    .map { TempPoint(it.takenAt, it.value, TempSite.fromKey(it.site)) },
                symptoms = dao.getSymptomsForEpisode(episodeId)
                    .map { SymptomPoint(it.name, it.severity, it.startedAt, it.endedAt) },
                doses = dao.getDosesForEpisode(episodeId)
                    .map { DosePoint(it.medicationName, it.takenAt, it.amount, it.unit) }
            ),
            nowMillis
        )
    }

    companion object {
        /** How far back a newly-started episode reaches to adopt records already taken. */
        const val DEFAULT_BACKFILL_MS: Long = 12L * 60 * 60 * 1000
    }
}
