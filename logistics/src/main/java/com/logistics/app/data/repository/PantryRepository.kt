package com.logistics.app.data.repository

import com.logistics.app.data.db.dao.PantryDao
import com.logistics.app.data.db.entities.GroceryItemEntity
import com.logistics.app.data.db.entities.ImportBatchEntity
import com.logistics.app.data.db.entities.PantryItemEntity
import com.logistics.app.data.db.entities.PantryTxnEntity
import com.logistics.app.data.model.GroceryItem
import com.logistics.app.data.model.GrocerySource
import com.logistics.app.data.model.ImportBatch
import com.logistics.app.data.model.ImportSource
import com.logistics.app.data.model.MealLine
import com.logistics.app.data.model.MealLog
import com.logistics.app.data.model.PantryItem
import com.logistics.app.data.model.PantryTxn
import com.logistics.app.data.model.PantryTxnReason
import com.logistics.app.data.model.ParsedOrder
import com.logistics.app.logic.GroceryPlanner
import com.logistics.app.logic.PantryUnits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    fun observeGrocery(): Flow<List<GroceryItem>> = dao.observeGrocery().map { list -> list.map { it.toModel() } }

    /**
     * Past meals for the History screen, newest first — every CONSUME ledger row rolled up into the
     * meal it belonged to. Grouping is by [PantryTxn.mealLogId]; rows written before the v2 migration
     * have none, so they fall back to grouping by meal name + timestamp (a best-effort reconstruction
     * of the meals that were logged before grouping existed). Item names/units are resolved against
     * live pantry rows so a remake targets the right shelf line.
     */
    fun observeMealHistory(): Flow<List<MealLog>> =
        combine(dao.observeConsumption(), dao.observeAll()) { txns, items ->
            val byId = items.associateBy { it.id }
            txns.groupBy { it.mealLogId ?: "legacy:${it.mealName}@${it.createdAt}" }
                .map { (groupKey, rows) ->
                    val head = rows.first()
                    MealLog(
                        id = head.mealLogId ?: groupKey,
                        mealName = head.mealName ?: "Meal",
                        recipeId = head.recipeId,
                        // Rows in a group share an instant; use the earliest as the meal's stamp.
                        loggedAt = rows.minOf { it.createdAt },
                        lines = rows.map { txn ->
                            val item = byId[txn.pantryItemId]
                            MealLine(
                                pantryItemId = txn.pantryItemId,
                                name = item?.name ?: "(removed item)",
                                // delta is negative for a use; History shows the amount consumed.
                                amount = -txn.delta,
                                unit = txn.unit,
                                available = item != null
                            )
                        }
                    )
                }
                .sortedByDescending { it.loggedAt }
        }

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
    ): Int {
        val name = mealName.trim().ifBlank { "Meal" }
        val now = now()
        // One id for the whole meal so its CONSUME rows can be regrouped — and replayed — as a unit.
        val mealLogId = UUID.randomUUID().toString()
        var deducted = 0
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
                recipeId = recipeId,
                mealLogId = mealLogId
            )
            deducted++
        }
        return deducted
    }

    /**
     * Re-log a past [MealLog] — deducts each of its lines from the pantry again (clamped to what's on
     * hand), landing a fresh meal in the ledger with the same name/recipe. Returns how many lines were
     * actually deducted (0 if nothing on those shelves is left). Lines whose pantry row no longer
     * exists are skipped by [consumeMeal].
     */
    suspend fun remakeMeal(meal: MealLog): Int =
        consumeMeal(
            mealName = meal.mealName,
            recipeId = meal.recipeId,
            consumptions = meal.lines.map { Consumption(it.pantryItemId, it.amount) }
        )

    // --- grocery list ("what to buy") ---

    /**
     * Add (or top up) one grocery line. Dedupes by name case-insensitively so hand-adding a product
     * already on the list — or re-running "restock low" / "from recipe" — bumps the existing line's
     * quantity instead of duplicating it (and un-checks it, since you clearly still need more). Fills
     * in a catalog link/category only when the existing line lacked one. Returns the resulting line.
     */
    suspend fun addGroceryItem(
        name: String,
        quantity: Double = 1.0,
        unit: String = "unit",
        category: String? = null,
        foodItemId: String? = null,
        source: GrocerySource = GrocerySource.MANUAL,
        recipeId: String? = null
    ): GroceryItem {
        val clean = name.trim()
        val now = now()
        val existing = dao.getGroceryByName(clean)
        val entity = if (existing != null) {
            existing.copy(
                quantity = existing.quantity + quantity.coerceAtLeast(0.0),
                unit = existing.unit.ifBlank { unit },
                category = existing.category ?: category,
                foodItemId = existing.foodItemId ?: foodItemId,
                recipeId = existing.recipeId ?: recipeId,
                checked = false,
                updatedAt = now
            )
        } else {
            GroceryItemEntity(
                id = UUID.randomUUID().toString(),
                foodItemId = foodItemId,
                name = clean,
                quantity = quantity.coerceAtLeast(0.0).let { if (it == 0.0) 1.0 else it },
                unit = unit.ifBlank { "unit" },
                category = category,
                source = source.value,
                recipeId = recipeId,
                checked = false,
                note = null,
                createdAt = now,
                updatedAt = now
            )
        }
        dao.upsertGrocery(entity)
        return entity.toModel()
    }

    suspend fun setGroceryChecked(id: String, checked: Boolean) {
        val item = dao.getGroceryById(id) ?: return
        dao.upsertGrocery(item.copy(checked = checked, updatedAt = now()))
    }

    suspend fun setGroceryQuantity(id: String, quantity: Double) {
        val item = dao.getGroceryById(id) ?: return
        dao.upsertGrocery(item.copy(quantity = quantity.coerceAtLeast(0.0), updatedAt = now()))
    }

    suspend fun removeGroceryItem(id: String) = dao.deleteGrocery(id)
    suspend fun clearCheckedGrocery() = dao.deleteCheckedGrocery()
    suspend fun clearGrocery() = dao.clearGrocery()

    /**
     * Sweep the pantry for anything at/under its alert level (or simply run out) and add each to the
     * grocery list, suggesting how many to buy via [GroceryPlanner.restockQuantity]. Nets out whatever
     * that line already has on the grocery list, so re-running the sweep tops the list up to the
     * target instead of stacking another full deficit on top each time. Returns how many lines were
     * added or topped up.
     */
    suspend fun addLowStockToGrocery(): Int {
        var added = 0
        for (item in dao.getAll()) {
            val low = (item.lowStockThreshold != null && item.quantity <= item.lowStockThreshold) ||
                item.quantity <= 0.0
            if (!low) continue
            val alreadyOnList = dao.getGroceryByName(item.name)?.quantity ?: 0.0
            val quantity = GroceryPlanner.restockQuantity(item.quantity, item.lowStockThreshold, alreadyOnList)
            if (quantity <= 0.0) continue
            addGroceryItem(
                name = item.name,
                quantity = quantity,
                unit = item.unit,
                category = item.category,
                foodItemId = item.foodItemId,
                source = GrocerySource.LOW_STOCK
            )
            added++
        }
        return added
    }

    /**
     * Add a recipe's *missing* ingredients — the catalog foods it needs that aren't already on the
     * shelf (matched by id first, then name; only counting rows that actually have stock) or already
     * sitting on the grocery list. Returns how many lines were added. Requires [catalog.ingredientFoods]
     * to resolve the recipe.
     */
    suspend fun addRecipeMissingToGrocery(recipeId: String): Int {
        val needed = catalog.ingredientFoods(recipeId)
        if (needed.isEmpty()) return 0
        val pantry = dao.getAll().filter { it.quantity > 0.0 }
        val stockedIds = pantry.mapNotNull { it.foodItemId }.toSet()
        val stockedNames = pantry.map { it.name }.toSet()
        val onListNames = dao.getAllGrocery().map { it.name }.toSet()
        val missing = GroceryPlanner.missingIngredients(needed, stockedIds, stockedNames, onListNames)
        for (food in missing) {
            addGroceryItem(
                name = food.name,
                quantity = 1.0,
                unit = PantryUnits.guessUnit(food.name),
                category = PantryUnits.guessCategory(food.name),
                foodItemId = food.foodItemId,
                source = GrocerySource.RECIPE,
                recipeId = recipeId
            )
        }
        return missing.size
    }

    /**
     * "Bought it" — move every checked grocery line onto the pantry shelf: top up the matching pantry
     * row (by catalog id, then name) or create a new one, record a RESTOCK ledger entry, and clear the
     * line off the list. This closes the loop shop → shelf. Returns how many lines were shelved.
     */
    suspend fun purchaseCheckedIntoPantry(): Int {
        val now = now()
        var count = 0
        for (g in dao.getCheckedGrocery()) {
            if (g.quantity <= 0.0) { dao.deleteGrocery(g.id); continue }
            val existing = g.foodItemId?.let { dao.getByFoodItemId(it) } ?: dao.getByName(g.name)
            if (existing != null) {
                dao.upsert(
                    existing.copy(
                        quantity = existing.quantity + g.quantity,
                        category = existing.category ?: g.category,
                        foodItemId = existing.foodItemId ?: g.foodItemId,
                        updatedAt = now
                    )
                )
                recordTxn(existing.id, g.quantity, existing.unit, PantryTxnReason.RESTOCK, note = "From grocery list")
            } else {
                val foodId = g.foodItemId ?: catalog.findFoodByName(g.name)?.id
                val item = PantryItemEntity(
                    id = UUID.randomUUID().toString(),
                    foodItemId = foodId,
                    name = g.name,
                    quantity = g.quantity,
                    unit = g.unit,
                    category = g.category,
                    lowStockThreshold = null,
                    note = null,
                    createdAt = now,
                    updatedAt = now
                )
                dao.upsert(item)
                recordTxn(item.id, g.quantity, item.unit, PantryTxnReason.RESTOCK, note = "From grocery list")
            }
            dao.deleteGrocery(g.id)
            count++
        }
        return count
    }

    // --- repackage ("break a unit into individual pieces") ---

    /**
     * Re-express one stock line at a finer granularity: the same physical stock, a different unit and
     * count ("2 lb" → "3 meals", "1 unit of 58-count" → "58 pieces"). It overwrites the item's
     * quantity/unit and lands a SPLIT ledger row noting the before/after, so the shelf stays
     * auditable. No-op returning null if the item is gone or the target count is not positive.
     */
    suspend fun splitItem(itemId: String, pieces: Double, pieceUnit: String, note: String? = null): PantryItem? {
        if (pieces <= 0.0) return null
        val item = dao.getById(itemId) ?: return null
        val unit = pieceUnit.trim().ifBlank { "piece" }
        val ledgerNote = note?.takeIf { it.isNotBlank() }
            ?: PantryUnits.splitNote(item.quantity, item.unit, pieces, unit)
        dao.upsert(item.copy(quantity = pieces, unit = unit, updatedAt = now()))
        recordTxn(item.id, pieces, unit, PantryTxnReason.SPLIT, note = ledgerNote)
        return dao.getById(itemId)?.toModel()
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
        mealLogId: String? = null,
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
                mealLogId = mealLogId,
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
        importBatchId = importBatchId, mealLogId = mealLogId, note = note, createdAt = createdAt
    )

    private fun ImportBatchEntity.toModel() = ImportBatch(
        id = id, source = ImportSource.from(source), orderNumber = orderNumber,
        itemCount = itemCount, label = label, createdAt = createdAt
    )

    private fun GroceryItemEntity.toModel() = GroceryItem(
        id = id, foodItemId = foodItemId, name = name, quantity = quantity, unit = unit,
        category = category, source = GrocerySource.from(source), recipeId = recipeId,
        checked = checked, note = note, createdAt = createdAt, updatedAt = updatedAt
    )
}
