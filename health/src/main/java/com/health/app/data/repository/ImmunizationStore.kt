package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.ImmunizationEntity
import com.health.app.data.model.Immunization
import com.health.app.logic.Immunizations
import com.health.app.logic.VaccineSeries
import com.health.app.logic.VaccineSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The vaccination record, and the series `logic/Immunizations` groups it into.
 *
 * Separate from [StandingRecordStore] because a vaccination is an event with a date rather than a
 * standing fact, even though both end up on the same screen.
 */
class ImmunizationStore(
    private val dao: HealthDao
) {

    fun observeImmunizations(profileId: String): Flow<List<Immunization>> =
        dao.observeImmunizations(profileId).map { rows -> rows.map { it.toModel() } }

    /**
     * The record as it is read: grouped into series, most recently given first.
     *
     * The grouping is `logic/Immunizations`' rather than SQL's, because deciding that "M.M.R." and
     * "MMR" are one vaccine is a judgement about names and the database has no opinion about it.
     */
    fun observeVaccineSeries(profileId: String): Flow<List<VaccineSeries>> =
        dao.observeImmunizations(profileId).map { rows ->
            Immunizations.group(rows.map { it.toModel().dose })
        }

    suspend fun addImmunization(
        profileId: String,
        vaccine: String,
        givenDate: String? = null,
        doseNumber: Int? = null,
        source: VaccineSource = VaccineSource.UNKNOWN,
        cvxCode: String? = null,
        providerId: String? = null,
        lotNumber: String? = null,
        site: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertImmunization(
            ImmunizationEntity(
                id = id,
                profileId = profileId,
                vaccine = vaccine.trim(),
                cvxCode = cvxCode.clean(),
                givenDate = givenDate.clean(),
                doseNumber = doseNumber,
                source = source.key,
                providerId = providerId.clean(),
                lotNumber = lotNumber.clean(),
                site = site.clean(),
                note = note.clean(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        return id
    }

    suspend fun updateImmunization(immunization: Immunization) {
        val existing = dao.getImmunization(immunization.id) ?: return
        dao.upsertImmunization(
            existing.copy(
                vaccine = immunization.vaccine.trim(),
                cvxCode = immunization.cvxCode.clean(),
                givenDate = immunization.givenDate.clean(),
                doseNumber = immunization.doseNumber,
                source = immunization.source.key,
                providerId = immunization.providerId.clean(),
                lotNumber = immunization.lotNumber.clean(),
                site = immunization.site.clean(),
                note = immunization.note.clean(),
                updatedAt = now()
            )
        )
    }

    suspend fun deleteImmunization(id: String): RestorableDelete? {
        val row = dao.getImmunization(id) ?: return null
        dao.deleteImmunization(id)
        // As for a condition: the certificate filed against this dose still names this id.
        return RestorableDelete { dao.upsertImmunization(row) }
    }
}
