package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.AllergyEntity
import com.health.app.data.db.entities.ConditionEntity
import com.health.app.data.model.Allergy
import com.health.app.data.model.Condition
import com.health.app.data.model.Medication
import com.health.app.data.model.ReadingType
import com.health.app.data.model.StandingRecord
import com.health.app.logic.Allergies
import com.health.app.logic.AllergyKind
import com.health.app.logic.AllergySeverity
import com.health.app.logic.AllergyWarning
import com.health.app.logic.ConditionStatus
import com.health.app.logic.Conditions
import com.health.app.logic.Documents
import com.health.app.logic.MedicineFacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The facts that are true between illnesses: allergies and ongoing conditions.
 *
 * Ordered worst-first here rather than in SQL, for the reason the DAO's own note gives — severity
 * is a judgement about what a reception desk needs to see first, and the database has no opinion.
 */
class StandingRecordStore(
    private val dao: HealthDao
) {

    fun observeAllergies(profileId: String): Flow<List<Allergy>> =
        dao.observeAllergies(profileId).map { rows -> sortAllergies(rows.map { it.toModel() }) }

    fun observeConditions(profileId: String): Flow<List<Condition>> =
        dao.observeConditions(profileId).map { rows -> sortConditions(rows.map { it.toModel() }) }

    /** One person's standing facts as a unit — what the record screen and the Today strip both read. */
    fun observeStandingRecord(profileId: String): Flow<StandingRecord> =
        combine(
            dao.observeAllergies(profileId),
            dao.observeConditions(profileId)
        ) { allergies, conditions ->
            StandingRecord(
                allergies = sortAllergies(allergies.map { it.toModel() }),
                conditions = sortConditions(conditions.map { it.toModel() })
            )
        }

    suspend fun standingRecordOnce(profileId: String): StandingRecord = StandingRecord(
        allergies = sortAllergies(dao.getAllergies(profileId).map { it.toModel() }),
        conditions = sortConditions(dao.getConditions(profileId).map { it.toModel() })
    )

    /**
     * Worst first, and the ordering lives here rather than in SQL for the reason the DAO's own note
     * gives: severity is stored as its key, so ordering by the column alphabetically would file
     * "mild" above "severe". The enum in `logic/` is the only definition of worse, and this is the
     * one place it is applied.
     */
    private fun sortAllergies(allergies: List<Allergy>): List<Allergy> =
        allergies.sortedWith(
            compareByDescending<Allergy> { it.severity.ordinal }
                .thenBy { it.kind.ordinal }
                .thenBy { it.substance.lowercase() }
        )

    private fun sortConditions(conditions: List<Condition>): List<Condition> =
        Conditions.sort(conditions, status = { it.status }, onset = { it.onsetDate }, name = { it.name })

    suspend fun addAllergy(
        profileId: String,
        substance: String,
        kind: AllergyKind,
        severity: AllergySeverity,
        reaction: String? = null,
        rxcui: String? = null,
        noticedDate: String? = null,
        note: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertAllergy(
            AllergyEntity(
                id = id,
                profileId = profileId,
                substance = substance.trim(),
                kind = kind.key,
                severity = severity.key,
                reaction = reaction.clean(),
                rxcui = rxcui.clean(),
                noticedDate = noticedDate.clean(),
                note = note.clean(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        return id
    }

    suspend fun updateAllergy(allergy: Allergy) {
        val existing = dao.getAllergy(allergy.id) ?: return
        dao.upsertAllergy(
            existing.copy(
                substance = allergy.substance.trim(),
                kind = allergy.kind.key,
                severity = allergy.severity.key,
                reaction = allergy.reaction.clean(),
                rxcui = allergy.rxcui.clean(),
                noticedDate = allergy.noticedDate.clean(),
                note = allergy.note.clean(),
                updatedAt = now()
            )
        )
    }

    suspend fun deleteAllergy(id: String): RestorableDelete? {
        val row = dao.getAllergy(id) ?: return null
        dao.deleteAllergy(id)
        return RestorableDelete { dao.upsertAllergy(row) }
    }

    suspend fun addCondition(
        profileId: String,
        name: String,
        status: ConditionStatus = ConditionStatus.ACTIVE,
        onsetDate: String? = null,
        resolvedDate: String? = null,
        providerId: String? = null,
        monitorReadingType: ReadingType? = null,
        note: String? = null
    ): String {
        val id = newId()
        val timestamp = now()
        dao.upsertCondition(
            ConditionEntity(
                id = id,
                profileId = profileId,
                name = name.trim(),
                status = status.key,
                onsetDate = onsetDate.clean(),
                resolvedDate = resolvedDate.clean(),
                providerId = providerId.clean(),
                monitorReadingType = monitorReadingType?.key,
                note = note.clean(),
                createdAt = timestamp,
                updatedAt = timestamp
            )
        )
        return id
    }

    suspend fun updateCondition(condition: Condition) {
        val existing = dao.getCondition(condition.id) ?: return
        dao.upsertCondition(
            existing.copy(
                name = condition.name.trim(),
                status = condition.status.key,
                onsetDate = condition.onsetDate.clean(),
                resolvedDate = condition.resolvedDate.clean(),
                providerId = condition.providerId.clean(),
                monitorReadingType = condition.monitorReadingType?.key,
                note = condition.note.clean(),
                updatedAt = now()
            )
        )
    }

    suspend fun deleteCondition(id: String): RestorableDelete? {
        val row = dao.getCondition(id) ?: return null
        dao.deleteCondition(id)
        // Documents filed against it keep pointing at this id, and find it again on the way back.
        return RestorableDelete { dao.upsertCondition(row) }
    }

    /**
     * Everything recorded for this person that matches a medicine, worst first.
     *
     * The ingredients come from the cached monograph when the product was looked up, which is what
     * makes the check worth having: a household that searched for a medicine gets matched against
     * the label's own ingredient list, while one that typed the name in gets matched against the
     * name. Both are honest; they are not equally strong, and `logic/Allergies` says which is which.
     *
     * An **empty list is not a clearance.** It means nothing recorded matched — see the note on
     * `logic/Allergies`. Callers must render it as that and never as an all-clear.
     */
    suspend fun allergyWarnings(
        profileId: String,
        name: String,
        rxcui: String? = null
    ): List<AllergyWarning> {
        val allergies = dao.getAllergies(profileId).map { it.toModel().facts }
        if (allergies.isEmpty()) return emptyList()
        val monograph = rxcui?.clean()?.let { dao.getDrugFacts(it)?.let { row -> DrugFactsMapper.toMonograph(row) } }
        return Allergies.check(
            allergies,
            MedicineFacts(
                rxcui = rxcui.clean(),
                name = name.trim(),
                brandName = monograph?.brandName,
                genericName = monograph?.genericName,
                ingredients = monograph?.ingredients.orEmpty()
            )
        )
    }

    /** The same check for a medicine already on somebody's list — used when a dose is about to be given. */
    suspend fun allergyWarnings(medication: Medication): List<AllergyWarning> =
        allergyWarnings(medication.profileId, medication.name, medication.rxcui)
}
