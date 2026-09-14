package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.DoseEntity
import com.health.app.data.model.Dose
import com.health.app.data.model.Medication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Doses actually taken.

 * A dose is the one write in Health that moves two things at once: the history gains a row and the
 * bottle loses some of its contents. The second half is [CabinetStock]'s, under its own same-unit
 * rule, so that this class stays a record of what happened rather than a stock-keeping system.
 */
class DoseStore(
    private val dao: HealthDao,
    private val episodes: EpisodeFiling,
    private val stock: CabinetStock,
    private val onReminderChange: (medicationId: String?) -> Unit
) {

    fun observeDoses(profileId: String): Flow<List<Dose>> =
        dao.observeDoses(profileId).map { rows -> rows.map { it.toModel() } }

    /**
     * Record a dose. The medicine's name is copied onto the row rather than referenced, so the
     * history stays readable after the medicine itself is renamed or removed.
     */
    suspend fun logDose(
        profileId: String,
        medicationId: String?,
        medicationName: String,
        amount: Double,
        unit: String,
        takenAt: Long = now(),
        note: String? = null
    ): String {
        val id = newId()
        dao.upsertDose(
            DoseEntity(
                id = id,
                profileId = profileId,
                medicationId = medicationId,
                medicationName = medicationName.trim(),
                amount = amount,
                unit = unit.trim(),
                takenAt = takenAt,
                note = note?.trim()?.ifBlank { null },
                episodeId = episodes.episodeIdAt(profileId, takenAt),
                createdAt = now()
            )
        )
        medicationId?.let { medication ->
            stock.drawFromCabinet(medication, amount, unit)
            // A "when the next dose is due" reminder is measured from the last dose, so the dose
            // that was just given is exactly the event that moves it.
            onReminderChange(medication)
        }
        return id
    }

    /** Give the medicine its own default dose — the one-tap path from the Today screen. */
    suspend fun logDoseOf(medication: Medication, takenAt: Long = now(), note: String? = null): String =
        logDose(
            profileId = medication.profileId,
            medicationId = medication.id,
            medicationName = medication.name,
            amount = medication.doseAmount ?: 0.0,
            unit = medication.doseUnit,
            takenAt = takenAt,
            note = note
        )

    /**
     * Remove a dose that was never given — a mis-tap, or a dose recorded twice because two people
     * both reached for the phone.
     *
     * Deleting it puts the stock back, on the same same-unit rule [CabinetStock.drawFromCabinet]
     * deducts under.
     * The inverse has to exist: without it, a household that fat-fingers one dose is left with a
     * bottle that Health believes is emptier than it is, and no way to say otherwise except by
     * re-typing the quantity.
     */
    suspend fun deleteDose(id: String): RestorableDelete? {
        val dose = dao.getDose(id) ?: return null
        dao.deleteDose(id)
        val medicationId = dose.medicationId
        if (medicationId != null) {
            stock.returnToCabinet(medicationId, dose.amount, dose.unit)
            onReminderChange(medicationId)
        }
        // The inverse of the delete, not just of the row write: the stock this dose put back comes
        // out of the bottle again, under the same same-unit rule it was drawn on in the first place.
        return RestorableDelete {
            dao.upsertDose(dose)
            if (medicationId != null) {
                stock.drawFromCabinet(medicationId, dose.amount, dose.unit)
                onReminderChange(medicationId)
            }
        }
    }
}
