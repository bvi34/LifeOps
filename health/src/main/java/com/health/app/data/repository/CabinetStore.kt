package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.db.entities.CabinetItemEntity
import com.health.app.data.model.CabinetEntry
import com.health.app.data.model.CabinetItem
import com.health.app.data.model.CabinetUse
import com.health.app.logic.Cabinet
import com.health.app.logic.DoseRecord
import com.health.app.logic.DoseSchedule
import com.health.app.logic.DrugMonograph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The medicine cabinet: the bottles themselves, household-scoped, and the drug facts looked up
 * about what is in them.
 *
 * Household-scoped deliberately — one bottle of paracetamol is one bottle however many people in
 * the house are taking from it, and a cabinet that counted it twice would be lying about the stock.
 */
class CabinetStore(
    private val dao: HealthDao
) {

    fun observeCabinetItems(): Flow<List<CabinetItem>> =
        dao.observeCabinetItems().map { rows -> rows.map { it.toModel() } }

    /**
     * The cabinet as it is actually read: every item with its expiry and stock verdicts, the label
     * Health cached for it, and each person who takes it with their own dose and their own live dose
     * window.
     *
     * The last part is the whole point of the tab. Standing in front of a bottle, the question is
     * almost never "what is this" — it is "how much of this does *she* get, and can she have some
     * yet". Assembling that here means one flow answers it for every person at once, rather than
     * five screens each re-deriving it and disagreeing at the edges.
     *
     * Items sort by [com.health.app.logic.CabinetStatus.sortRank]: expired first, then out of stock,
     * then expiring soon, then running low, then everything that is simply fine. A cabinet is read
     * top-down when something is wrong and searched by name when nothing is, so the ordering serves
     * the first case and the name sort inside each rank serves the second.
     */
    fun observeCabinet(): Flow<List<CabinetEntry>> =
        combine(
            dao.observeCabinetItems(),
            dao.observeProfiles(),
            dao.observeAllDrugFacts(),
            dao.observeAllMedications(),
            dao.observeAllDosesSince(now() - DoseSchedule.WINDOW_MS)
        ) { items, profiles, facts, medications, doses ->
            val nowMillis = now()
            val profilesById = profiles.associateBy { it.id }
            val monographs = facts.associate { it.rxcui to DrugFactsMapper.toMonograph(it) }

            items.map { item ->
                val uses = medications
                    .filter { it.cabinetItemId == item.id }
                    .mapNotNull { medication ->
                        val profile = profilesById[medication.profileId] ?: return@mapNotNull null
                        val history = doses
                            .filter { it.medicationId == medication.id }
                            .map { DoseRecord(it.takenAt, it.amount) }
                        CabinetUse(
                            profile = profile.toModel(),
                            medication = medication.toModel(),
                            window = DoseSchedule.evaluate(medication.toRule(), history, nowMillis)
                        )
                    }
                    .sortedBy { it.profile.sortOrder }

                // Stock is measured against a dose, and different people take different doses. The
                // item's own verdict uses the largest of them — the one that runs the bottle down
                // first — so "enough for one more dose" is never a promise made about the wrong
                // person. With nobody assigned there is no dose to measure against, and the stock
                // is simply reported as it stands.
                val reference = uses.maxByOrNull { it.medication.doseAmount ?: 0.0 }?.medication
                val status = Cabinet.assess(
                    item.toFacts(reference?.doseAmount, reference?.doseUnit.orEmpty())
                )

                CabinetEntry(
                    item = item.toModel(),
                    status = status,
                    monograph = item.rxcui?.let { monographs[it] },
                    takenBy = uses
                )
            }.sortedWith(compareBy({ it.status.sortRank }, { it.item.name.lowercase() }))
        }

    suspend fun getCabinetItem(id: String): CabinetItem? = dao.getCabinetItem(id)?.toModel()

    /**
     * Put something in the cabinet. Only the name is required — a cabinet that has to be filled in
     * completely is a cabinet that stays empty, and "there's Calpol in the bathroom" is already
     * worth more than nothing.
     */
    suspend fun addCabinetItem(
        name: String,
        rxcui: String? = null,
        brandName: String? = null,
        strength: String? = null,
        form: String? = null,
        quantity: Double? = null,
        quantityUnit: String = "",
        expiryDate: String? = null,
        location: String? = null,
        lowStockThreshold: Double? = null,
        note: String? = null
    ): String {
        val id = newId()
        val stamp = now()
        dao.upsertCabinetItem(
            CabinetItemEntity(
                id = id,
                rxcui = rxcui?.trim()?.ifBlank { null },
                name = name.trim(),
                brandName = brandName?.trim()?.ifBlank { null },
                strength = strength?.trim()?.ifBlank { null },
                form = form?.trim()?.ifBlank { null },
                quantity = quantity,
                quantityUnit = quantityUnit.trim(),
                expiryDate = expiryDate?.trim()?.ifBlank { null },
                location = location?.trim()?.ifBlank { null },
                lowStockThreshold = lowStockThreshold,
                note = note?.trim()?.ifBlank { null },
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return id
    }

    suspend fun updateCabinetItem(item: CabinetItem) {
        val existing = dao.getCabinetItem(item.id) ?: return
        dao.upsertCabinetItem(
            existing.copy(
                rxcui = item.rxcui?.trim()?.ifBlank { null },
                name = item.name.trim(),
                brandName = item.brandName?.trim()?.ifBlank { null },
                strength = item.strength?.trim()?.ifBlank { null },
                form = item.form?.trim()?.ifBlank { null },
                quantity = item.quantity,
                quantityUnit = item.quantityUnit.trim(),
                expiryDate = item.expiryDate?.trim()?.ifBlank { null },
                location = item.location?.trim()?.ifBlank { null },
                lowStockThreshold = item.lowStockThreshold,
                note = item.note?.trim()?.ifBlank { null },
                updatedAt = now()
            )
        )
    }

    /**
     * Restock — a new bottle of the same thing. Sets the quantity outright rather than adding to it
     * and takes the new box's expiry date, because that is what actually happened: the old bottle is
     * gone and this is a different one. Adding would carry the old bottle's remaining 20 mL into a
     * new bottle it is not in.
     */
    suspend fun restockCabinetItem(id: String, quantity: Double?, expiryDate: String?) {
        val existing = dao.getCabinetItem(id) ?: return
        dao.upsertCabinetItem(
            existing.copy(
                quantity = quantity,
                expiryDate = expiryDate?.trim()?.ifBlank { null },
                updatedAt = now()
            )
        )
    }

    /**
     * Throw a bottle away, keeping every medicine given from it and every dose recorded against it.
     * Binning the box does not mean the child stopped taking the medicine, and it certainly does not
     * un-happen the doses.
     */
    suspend fun deleteCabinetItem(id: String) {
        dao.deleteCabinetItemKeepingMedications(id)
        dao.pruneUnreferencedDrugFacts()
    }

    /** Point a person's medicine at a bottle — or, with null, stop tracking its stock. */
    suspend fun linkMedicationToCabinet(medicationId: String, cabinetItemId: String?) {
        val existing = dao.getMedication(medicationId) ?: return
        dao.upsertMedication(existing.copy(cabinetItemId = cabinetItemId))
    }

    // --- looked-up drug facts ---------------------------------------------------------------------

    /** The cached monograph for a product, if Health has ever looked it up. */
    suspend fun getMonograph(rxcui: String): DrugMonograph? =
        dao.getDrugFacts(rxcui)?.let { DrugFactsMapper.toMonograph(it) }

    fun observeMonograph(rxcui: String): Flow<DrugMonograph?> =
        dao.observeDrugFacts(rxcui).map { row -> row?.let { DrugFactsMapper.toMonograph(it) } }

    /**
     * Cache what a lookup found. Upserts by RxNorm concept id, so a refresh replaces the previous
     * answer for everybody who has that product rather than accumulating copies of it.
     */
    suspend fun saveMonograph(monograph: DrugMonograph) {
        val rxcui = monograph.rxcui?.trim()?.ifBlank { null } ?: return
        dao.upsertDrugFacts(DrugFactsMapper.toEntity(monograph, rxcui))
    }
}
