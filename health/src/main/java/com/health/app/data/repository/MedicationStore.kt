package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.MedicationEntity
import com.health.app.data.model.Medication
import com.health.app.data.model.MedicationStatus
import com.health.app.logic.Cabinet
import com.health.app.logic.DoseRecord
import com.health.app.logic.DoseReminder
import com.health.app.logic.DoseSchedule
import com.health.app.logic.ReminderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * A person's course of a medicine: what they are on, at what dose, and whether it should nudge.
 *
 * The bottle it comes out of is [CabinetStore]'s; the individual doses taken are [DoseStore]'s.
 * This class owns the prescription, not the stock and not the history.
 */
class MedicationStore(
    private val dao: HealthDao,
    private val onReminderChange: (medicationId: String?) -> Unit
) {

    fun observeMedications(profileId: String): Flow<List<Medication>> =
        dao.observeMedications(profileId).map { rows -> rows.map { it.toModel() } }

    /**
     * Each of a person's medicines with its current dose window. The dose query is windowed to the
     * last 24 hours because that is the only span any limit is written against — a person with years
     * of dose history should not re-read all of it to answer "can I give it yet?". The bound is
     * fixed when the flow is built, so a screen left open overnight reads a few rows that have since
     * aged out; that costs nothing, because [DoseSchedule.evaluate] applies the real window itself
     * against the current time rather than trusting the query's.
     */
    fun observeMedicationStatuses(profileId: String): Flow<List<MedicationStatus>> =
        combine(
            dao.observeMedications(profileId),
            dao.observeDosesSince(profileId, now() - DoseSchedule.WINDOW_MS),
            dao.observeCabinetItems(),
            dao.observeAllDrugFacts()
        ) { medications, doses, cabinet, facts ->
            val nowMillis = now()
            val byId = cabinet.associateBy { it.id }
            val monographs = facts.associate { it.rxcui to DrugFactsMapper.toMonograph(it) }
            medications.map { medication ->
                val history = doses
                    .filter { it.medicationId == medication.id }
                    .map { DoseRecord(it.takenAt, it.amount) }
                val item = medication.cabinetItemId?.let { byId[it] }
                MedicationStatus(
                    medication = medication.toModel(),
                    window = DoseSchedule.evaluate(medication.toRule(), history, nowMillis),
                    cabinetItem = item?.toModel(),
                    cabinetStatus = item?.let {
                        Cabinet.assess(it.toFacts(medication.doseAmount, medication.doseUnit))
                    },
                    monograph = medication.rxcui?.let { monographs[it] }
                )
            }
        }

    suspend fun addMedication(
        profileId: String,
        name: String,
        strength: String?,
        form: String?,
        doseAmount: Double?,
        doseUnit: String,
        minIntervalHours: Double?,
        maxDosesPer24h: Int?,
        maxAmountPer24h: Double?,
        note: String? = null,
        rxcui: String? = null,
        cabinetItemId: String? = null,
        reminderMode: ReminderMode = ReminderMode.OFF,
        reminderTimes: List<java.time.LocalTime> = emptyList()
    ): String {
        val id = newId()
        dao.upsertMedication(
            MedicationEntity(
                id = id,
                profileId = profileId,
                name = name.trim(),
                strength = strength?.trim()?.ifBlank { null },
                form = form?.trim()?.ifBlank { null },
                doseAmount = doseAmount,
                doseUnit = doseUnit.trim(),
                minIntervalHours = minIntervalHours,
                maxDosesPer24h = maxDosesPer24h,
                maxAmountPer24h = maxAmountPer24h,
                note = note?.trim()?.ifBlank { null },
                active = true,
                createdAt = now(),
                rxcui = rxcui?.trim()?.ifBlank { null },
                cabinetItemId = cabinetItemId,
                reminderMode = reminderMode.key,
                reminderTimes = DoseReminder.formatTimes(reminderTimes).ifBlank { null }
            )
        )
        onReminderChange(id)
        return id
    }

    suspend fun updateMedication(medication: Medication) {
        val existing = dao.getMedication(medication.id) ?: return
        dao.upsertMedication(
            existing.copy(
                name = medication.name.trim(),
                strength = medication.strength?.trim()?.ifBlank { null },
                form = medication.form?.trim()?.ifBlank { null },
                doseAmount = medication.doseAmount,
                doseUnit = medication.doseUnit.trim(),
                minIntervalHours = medication.minIntervalHours,
                maxDosesPer24h = medication.maxDosesPer24h,
                maxAmountPer24h = medication.maxAmountPer24h,
                note = medication.note?.trim()?.ifBlank { null },
                active = medication.active,
                rxcui = medication.rxcui?.trim()?.ifBlank { null },
                cabinetItemId = medication.cabinetItemId,
                reminderMode = medication.reminderMode.key,
                reminderTimes = DoseReminder.formatTimes(medication.reminderTimes).ifBlank { null }
            )
        )
        onReminderChange(medication.id)
    }

    /**
     * Set (or clear) a medicine's reminder without touching anything else about it.
     *
     * Its own method rather than a field on [updateMedication] because it is reached from a
     * different place — a switch on the card, not the edit form — and because clearing the times
     * along with the mode is the only correct way to turn a set-times reminder off. Leaving them
     * behind would quietly re-arm the old schedule the next time somebody switched it back on.
     */
    suspend fun setMedicationReminder(
        medicationId: String,
        mode: ReminderMode,
        times: List<java.time.LocalTime> = emptyList()
    ) {
        val existing = dao.getMedication(medicationId) ?: return
        dao.upsertMedication(
            existing.copy(
                reminderMode = mode.key,
                reminderTimes = if (mode == ReminderMode.FIXED_TIMES) {
                    DoseReminder.formatTimes(times).ifBlank { null }
                } else {
                    null
                }
            )
        )
        onReminderChange(medicationId)
    }

    /**
     * Stop tracking a medicine for one person, keeping every dose already given from it.
     *
     * Restorable: the row goes back under its own id, so the doses that name it and the bottle it
     * was linked to find it again. The reminder is torn down and rebuilt on both paths, because a
     * medicine that comes back with no reminder is a medicine somebody stops being told to give.
     */
    suspend fun deleteMedication(id: String): RestorableDelete? {
        val row = dao.getMedication(id) ?: return null
        dao.deleteMedication(id)
        onReminderChange(id)
        return RestorableDelete {
            dao.upsertMedication(row)
            onReminderChange(id)
        }
    }
}
