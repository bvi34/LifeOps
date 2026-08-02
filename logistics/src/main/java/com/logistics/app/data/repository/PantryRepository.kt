package com.logistics.app.data.repository

import com.logistics.app.data.db.dao.PantryDao
import com.logistics.app.data.db.entities.ImportBatchEntity
import com.logistics.app.data.db.entities.PantryItemEntity
import com.logistics.app.data.db.entities.PantryTxnEntity
import com.logistics.app.data.model.ImportBatch
import com.logistics.app.data.model.ImportSource
import com.logistics.app.data.model.PantryItem
import com.logistics.app.data.model.PantryTxn
import com.logistics.app.data.model.PantryTxnReason
import com.logistics.app.data.model.ParsedOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * The heart of Logistics: the virtual pantry and its movement ledger. Every quantity change goes
 * through here and lands both a [PantryItemEntity] update and a [PantryTxnEntity] row, so the shelf
 * always has an auditable history ("added 84 from Walmart order #…", "used 2 in Taco Tuesday").
 */
class PantryRepository(
    private val dao: PantryDao,
    private val catalog: LifeOpsCatalog
) {

    // --- observation ---
    fun observeItems(): Flow<List<PantryItem>> = dao.observeAll().map { list -> list.map { it.toModel() } }
    fun observeBatches(): Flow<List<ImportBatch>> = dao.observeBatches().map { list -> list.map { it.toModel() } }
    fun observeConsumption(): Flow<List<PantryTxn>> = dao.observeConsumption().map { list -> list.map { it.toModel() } }
    fun observeTxnsForItem(itemId: String): Flow<List<PantryTxn>> =
        dao.observeTxnsForItem(itemId).map { list -> list.map { it.toModel() } }

    suspend fun getItem(id: String): PantryItem? = dao.getById(id)?.toModel()

    // --- manual edits ---

    /** Add a brand-new pantry line by hand. */
    suspend fun addItem(
        name: String,
        quantity: Double,
        unit: String,
        category: String?,
        foodItemId: String? = null
    ): PantryItem {
        val now = now()
        val item = PantryItemEntity(
            id = UUID.randomUUID().toString(),
            foodItemId = foodItemId,
            name = name.trim(),
            quantity = quantity.coerceAtLeast(0.0),
            unit = unit.ifBlank { "unit" },
            category = category,
            lowStockThreshold = null,
            note = null,
            createdAt = now,
            updatedAt = now
        )
        dao.upsert(item)
        recordTxn(item.id, quantity, unit, PantryTxnReason.MANUAL, note = "Added by hand")
        return item.toModel()
    }

    /** Set an item's stock to an exact value, logging the signed delta as a correction. */
    suspend fun setQuantity(itemId: String, newQuantity: Double, note: String? = null) {
        val item = dao.getById(itemId) ?: return
        val clamped = newQuantity.coerceAtLeast(0.0)
        val delta = clamped - item.quantity
        dao.upsert(item.copy(quantity = clamped, updatedAt = now()))
        if (delta != 0.0) recordTxn(itemId, delta, item.unit, PantryTxnReason.CORRECTION, note = note)
    }

    suspend fun updateThreshold(itemId: String, threshold: Double?) {
        val item = dao.getById(itemId) ?: return
        dao.upsert(item.copy(lowStockThreshold = threshold, updatedAt = now()))
    }

    suspend fun deleteItem(itemId: String) = dao.delete(itemId)

    // --- import ---

    /**
     * Turn a [ParsedOrder] into stock. Each line tops up an existing same-name pantry row (so a
     * repeat grocery run adds to the shelf rather than duplicating it) or creates a new one, links a
     * LifeOps catalog food when the name matches exactly, and records an IMPORT ledger entry tied to
     * the returned [ImportBatch].
     */
    suspend fun commitImport(order: ParsedOrder, source: ImportSource): ImportBatch {
        val now = now()
        val batchId = UUID.randomUUID().toString()
        val label = buildString {
            append(source.label)
            order.orderNumber?.let { append(" · order #$it") }
        }
        val batch = ImportBatchEntity(
            id = batchId,
            source = source.value,
            orderNumber = order.orderNumber,
            itemCount = order.lines.size,
            label = label,
            createdAt = now
        )
        dao.upsertBatch(batch)

        for (line in order.lines) {
            val existing = dao.getByName(line.rawName)
            if (existing != null) {
                dao.upsert(
                    existing.copy(
                        quantity = existing.quantity + line.quantity,
                        // Fill in a category/link if we didn't have one before.
                        category = existing.category ?: line.category,
                        updatedAt = now
                    )
                )
                recordTxn(existing.id, line.quantity.toDouble(), existing.unit, PantryTxnReason.IMPORT, importBatchId = batchId)
            } else {
                val foodId = catalog.findFoodByName(line.rawName)?.id
                val item = PantryItemEntity(
                    id = UUID.randomUUID().toString(),
                    foodItemId = foodId,
                    name = line.rawName,
                    quantity = line.quantity.toDouble(),
                    unit = line.unit,
                    category = line.category,
                    lowStockThreshold = null,
                    note = null,
                    createdAt = now,
                    updatedAt = now
                )
                dao.upsert(item)
                recordTxn(item.id, line.quantity.toDouble(), item.unit, PantryTxnReason.IMPORT, importBatchId = batchId)
            }
        }
        return batch.toModel()
    }

    // --- consumption ("for X meal, here's what I used") ---

    data class Consumption(val pantryItemId: String, val amount: Double)

    /**
     * Deduct a set of pantry items for a named meal (optionally tied to a LifeOps recipe). Each
     * deduction is clamped so stock never goes negative, and each lands a CONSUME ledger row stamped
     * with the meal name and recipe, which is exactly the "here's what I used" record.
     */
    suspend fun consumeMeal(
        mealName: String,
        recipeId: String?,
        consumptions: List<Consumption>
    ) {
        val name = mealName.trim().ifBlank { "Meal" }
        val now = now()
        for (c in consumptions) {
            if (c.amount <= 0.0) continue
            val item = dao.getById(c.pantryItemId) ?: continue
            val used = c.amount.coerceAtMost(item.quantity)
            if (used <= 0.0) continue
            dao.upsert(item.copy(quantity = item.quantity - used, updatedAt = now))
            recordTxn(
                itemId = item.id,
                delta = -used,
                unit = item.unit,
                reason = PantryTxnReason.CONSUME,
                mealName = name,
                recipeId = recipeId
            )
        }
    }

    // --- internals ---

    private suspend fun recordTxn(
        itemId: String,
        delta: Double,
        unit: String,
        reason: PantryTxnReason,
        mealName: String? = null,
        recipeId: String? = null,
        importBatchId: String? = null,
        note: String? = null
    ) {
        dao.upsertTxn(
            PantryTxnEntity(
                id = UUID.randomUUID().toString(),
                pantryItemId = itemId,
                delta = delta,
                unit = unit,
                reason = reason.value,
                mealName = mealName,
                recipeId = recipeId,
                importBatchId = importBatchId,
                note = note,
                createdAt = now()
            )
        )
    }

    private fun now(): String = Instant.now().toString()

    // --- mappers ---
    private fun PantryItemEntity.toModel() = PantryItem(
        id = id, foodItemId = foodItemId, name = name, quantity = quantity, unit = unit,
        category = category, lowStockThreshold = lowStockThreshold, note = note,
        createdAt = createdAt, updatedAt = updatedAt
    )

    private fun PantryTxnEntity.toModel() = PantryTxn(
        id = id, pantryItemId = pantryItemId, delta = delta, unit = unit,
        reason = PantryTxnReason.from(reason), mealName = mealName, recipeId = recipeId,
        importBatchId = importBatchId, note = note, createdAt = createdAt
    )

    private fun ImportBatchEntity.toModel() = ImportBatch(
        id = id, source = ImportSource.from(source), orderNumber = orderNumber,
        itemCount = itemCount, label = label, createdAt = createdAt
    )
}
