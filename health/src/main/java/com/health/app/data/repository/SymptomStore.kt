package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.SymptomEntity
import com.health.app.data.model.Symptom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What was observed rather than measured — a cough, a rash, a refusal to eat.

 * Separate from [ReadingStore] because a symptom has a span rather than a value: it starts, runs,
 * and is at some point declared over, and none of that is arithmetic.
 */
class SymptomStore(
    private val dao: HealthDao,
    private val episodes: EpisodeFiling
) {

    fun observeSymptoms(profileId: String): Flow<List<Symptom>> =
        dao.observeSymptoms(profileId).map { rows -> rows.map { it.toModel() } }

    suspend fun addSymptom(
        profileId: String,
        name: String,
        severity: Int,
        startedAt: Long = now(),
        note: String? = null
    ): String {
        val id = newId()
        dao.upsertSymptom(
            SymptomEntity(
                id = id,
                profileId = profileId,
                episodeId = episodes.episodeIdAt(profileId, startedAt),
                name = name.trim(),
                severity = severity.coerceIn(1, 5),
                startedAt = startedAt,
                endedAt = null,
                note = note?.trim()?.ifBlank { null },
                createdAt = now()
            )
        )
        return id
    }

    /** Mark a symptom as over — or, with [endedAt] null, as still going after all. */
    suspend fun setSymptomEnded(symptomId: String, endedAt: Long? = now()) {
        val row = dao.getSymptom(symptomId) ?: return
        dao.upsertSymptom(row.copy(endedAt = endedAt))
    }

    suspend fun deleteSymptom(id: String) = dao.deleteSymptom(id)
}
