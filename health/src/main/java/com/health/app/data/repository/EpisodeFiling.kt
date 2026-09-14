package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao

/**
 * Which illness a record belongs to, decided by **when the record happened** rather than by what
 * happens to be open when it is typed in.
 *
 * The two answers agree for anything recorded as it happens, and disagree the moment the history is
 * filled in afterwards — which it can be. Last night's dose, typed up over breakfast, belongs to
 * last night's illness; a week of last month's flu, reconstructed from memory, belongs to last
 * month's episode and not to today's cold. Filing by "what's open now" would put all of it in the
 * wrong story, and an episode summary is only worth reading if the rows under it actually happened
 * during it.
 *
 * Its own class rather than a method on [EpisodeStore] because four stores need this one judgement
 * and none of them needs the rest of what [EpisodeStore] can do. Depending on the whole of episodes
 * to answer "which one was open at 3am" is how a record store ends up able to close an illness.
 */
class EpisodeFiling(private val dao: HealthDao) {

    /** The id of the episode open at [atMillis] for this person, or null if none was. */
    suspend fun episodeIdAt(profileId: String, atMillis: Long): String? =
        dao.getEpisodeAt(profileId, atMillis)?.id
}
