package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.CareNoteEntity
import com.health.app.data.model.CareKind
import com.health.app.data.model.CareNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The free-text half of the record — what a nurse said, what was tried, what to watch for.
 *
 * Filed against an open illness on the same rule everything else is, so that an episode summary
 * reads as the whole story rather than only its numbers.
 */
class CareNoteStore(
    private val dao: HealthDao,
    private val episodes: EpisodeFiling
) {

    fun observeCareNotes(profileId: String): Flow<List<CareNote>> =
        dao.observeCareNotes(profileId).map { rows -> rows.map { it.toModel() } }

    suspend fun addCareNote(
        profileId: String,
        kind: CareKind,
        text: String,
        at: Long = now()
    ): String {
        val id = newId()
        dao.upsertCareNote(
            CareNoteEntity(
                id = id,
                profileId = profileId,
                episodeId = episodes.episodeIdAt(profileId, at),
                kind = kind.key,
                text = text.trim(),
                at = at,
                createdAt = now()
            )
        )
        return id
    }

    suspend fun deleteCareNote(id: String): RestorableDelete? {
        val row = dao.getCareNote(id) ?: return null
        dao.deleteCareNote(id)
        return RestorableDelete { dao.upsertCareNote(row) }
    }
}
