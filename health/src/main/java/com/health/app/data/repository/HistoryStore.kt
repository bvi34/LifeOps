package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.prefs.HealthPrefs
import com.health.app.logic.Age
import com.health.app.logic.Timeline
import com.health.app.logic.TimelineDay
import com.health.app.logic.TimelineFacts
import kotlinx.coroutines.flow.map

/**
 * The record read back as a timeline — one day per row, everything that happened on it.
 *
 * Reads across every other store's table and writes to none of them. It is the one place in this
 * package that is allowed to be nosy, because "what happened that week" is not any single table's
 * question.
 */
class HistoryStore(
    private val dao: HealthDao,
    private val prefs: HealthPrefs
) {

    /**
     * Everything that was done for one illness, in the order it was done.
     *
     * The other half of reading an episode back. `summarizeEpisode` answers *how did it go*; this
     * answers *what actually happened, and when* — which is the question a doctor asks, and the one
     * the person who was up all three nights cannot answer from memory.
     *
     * Read once on demand rather than observed, like the summary: it folds four tables, which is
     * worth doing when somebody opens the history and not worth redoing on every unrelated write.
     * The display units are read the same way, at the same moment, so the temperatures and weights
     * in the history are written in the scales the rest of the app is showing.
     */
    suspend fun episodeHistory(episodeId: String): List<TimelineDay> {
        val episode = dao.getEpisode(episodeId) ?: return emptyList()
        val profile = dao.getProfile(episode.profileId)
        val ageMonths = profile?.birthDate?.let { Age.monthsAt(it, now()) }
        val unit = prefs.temperatureUnit
        val weightUnit = prefs.weightUnit

        return Timeline.build(
            TimelineFacts(
                readings = dao.getReadingsForEpisode(episodeId).map { it.toTimelineEntry(ageMonths, unit, weightUnit) },
                symptoms = dao.getSymptomsForEpisode(episodeId).flatMap { it.toTimelineEntries() },
                doses = dao.getDosesForEpisode(episodeId).map { it.toTimelineEntry() },
                careNotes = dao.getCareNotesForEpisode(episodeId).map { it.toTimelineEntry() },
                episodeStartedAtMillis = episode.startedAt,
                episodeEndedAtMillis = episode.endedAt,
                episodeTitle = episode.title
            )
        )
    }

    /**
     * The same history for a person over a window, illness or no illness.
     *
     * Not everything worth reconstructing happened during a declared episode — the week of bad
     * headaches nobody called an illness, the doses given before anyone thought to start one — and a
     * history that could only be read inside an episode would quietly lose all of it. Days are not
     * numbered here, because there is no day one to count from.
     */
    suspend fun profileHistory(profileId: String, sinceMillis: Long): List<TimelineDay> {
        val profile = dao.getProfile(profileId)
        val ageMonths = profile?.birthDate?.let { Age.monthsAt(it, now()) }
        val unit = prefs.temperatureUnit
        val weightUnit = prefs.weightUnit

        return Timeline.build(
            TimelineFacts(
                readings = dao.getReadingsSince(profileId, sinceMillis).map { it.toTimelineEntry(ageMonths, unit, weightUnit) },
                symptoms = dao.getSymptomsSince(profileId, sinceMillis).flatMap { it.toTimelineEntries() },
                doses = dao.getDosesSince(profileId, sinceMillis).map { it.toTimelineEntry() },
                careNotes = dao.getCareNotesSince(profileId, sinceMillis).map { it.toTimelineEntry() }
            )
        )
    }
}
