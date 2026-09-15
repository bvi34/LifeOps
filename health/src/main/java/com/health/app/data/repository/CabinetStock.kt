package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.logic.Cabinet

/**
 * The arithmetic that runs when a dose is taken out of a bottle, or put back because it never was.
 *
 * Split out of both [DoseStore] and [CabinetStore] because it belongs squarely to neither: recording
 * a dose is a fact about a person, keeping a stock count is a fact about a bottle, and this is the
 * one place the two meet. Keeping it here is what lets [DoseStore] stay a history rather than a
 * stock-keeping system.
 *
 * Both directions run under the same same-unit rule — 15 mL out of 120 mL is arithmetic, 15 mL out
 * of "1 bottle" is a guess, and a stock count that quietly invents its own conversions is worse than
 * no stock count at all. Where the units differ the dose is still recorded in full; only the
 * deduction is skipped.
 */
class CabinetStock(private val dao: HealthDao) {

    /**
     * Take [amount] out of the bottle this medicine is drawn from.
     *
     * The stock floors at zero rather than going negative: a bottle that has run out has run out,
     * and a negative quantity on screen reads as a bug rather than as "you've used more than you
     * told me you had".
     */
    suspend fun drawFromCabinet(medicationId: String, amount: Double, unit: String) {
        val item = bottleFor(medicationId, amount, unit) ?: return
        val quantity = item.quantity ?: return
        dao.upsertCabinetItem(
            item.copy(quantity = (quantity - amount).coerceAtLeast(0.0), updatedAt = now())
        )
    }

    /** The inverse of [drawFromCabinet], under exactly the same same-unit rule. */
    suspend fun returnToCabinet(medicationId: String, amount: Double, unit: String) {
        val item = bottleFor(medicationId, amount, unit) ?: return
        val quantity = item.quantity ?: return
        dao.upsertCabinetItem(item.copy(quantity = quantity + amount, updatedAt = now()))
    }

    /**
     * The bottle this medicine draws from, if all of the conditions for adjusting its count hold:
     * a positive amount, a linked item, a counted item, and matching units.
     */
    private suspend fun bottleFor(medicationId: String, amount: Double, unit: String) =
        if (amount <= 0.0) {
            null
        } else {
            dao.getMedication(medicationId)?.cabinetItemId
                ?.let { dao.getCabinetItem(it) }
                ?.takeIf { it.quantity != null && Cabinet.sameUnit(it.quantityUnit, unit) }
        }
}
