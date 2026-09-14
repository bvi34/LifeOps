package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.model.Profile
import com.health.app.data.model.ProfileSnapshot
import com.health.app.data.model.ReadingType
import com.health.app.logic.CareLevel
import com.health.app.logic.Fever
import com.health.app.logic.TempSite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One person, right now — the single answer the Today screen exists to give.
 *
 * Assembled here rather than at the screen so that two renderers cannot derive two different care
 * levels from the same four tables.
 */
class SnapshotStore(
    private val dao: HealthDao,
    private val medications: MedicationStore
) {

    /**
     * Everything the Today screen shows for one person, assembled from four live sources so the
     * screen re-reads nothing itself. The care level is the highest call any part of it makes.
     */
    fun observeSnapshot(profile: Profile): Flow<ProfileSnapshot> =
        combine(
            dao.observeLatestOfType(profile.id, ReadingType.TEMPERATURE.key),
            dao.observeOpenEpisode(profile.id),
            dao.observeSymptoms(profile.id),
            medications.observeMedicationStatuses(profile.id)
        ) { latestTemp, openEpisode, symptoms, medications ->
            val nowMillis = now()
            val reading = latestTemp?.toModel()
            val assessment = reading?.let {
                Fever.assess(it.value, it.site ?: TempSite.ORAL, profile.ageMonthsAt(nowMillis))
            }
            ProfileSnapshot(
                profile = profile,
                latestTemperature = reading,
                temperatureAssessment = assessment,
                openEpisode = openEpisode?.toModel(),
                activeSymptoms = symptoms.filter { it.endedAt == null }
                    .map { it.toModel() }
                    .sortedByDescending { it.severity },
                medications = medications,
                careLevel = assessment?.careLevel ?: CareLevel.ROUTINE
            )
        }

    /** One person's current snapshot, read once — used by the Advisor bridge, which is not reactive. */
    suspend fun snapshotOnce(profile: Profile): ProfileSnapshot = observeSnapshot(profile).first()
}
