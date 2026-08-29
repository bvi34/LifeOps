package com.logistics.app.data.model

/**
 * One line of stock in the virtual pantry. The pantry is deliberately *package-centric*: Walmart
 * (and how people think about their shelves) counts whole packages — "2 bags of Doritos", "1 gallon
 * of milk" — not grams. So [quantity] is a count in [unit] (a free-form packaging word), which keeps
 * imports lossless and the shelf legible.
 *
 * [foodItemId] links to a LifeOps food-catalog item when we could match one, so nutrition and
 * recipes line up across the suite; it is null for a stock line we couldn't (or didn't) match. The
 * [name] is denormalized so a pantry row still reads correctly even if the catalog item is renamed
 * or removed.
 */
data class PantryItem(
    val id: String,
    val foodItemId: String? = null,
    val name: String,
    val quantity: Double,
    val unit: String = "unit",
    val category: String? = null,
    val lowStockThreshold: Double? = null,
    val note: String? = null,
    val createdAt: String,
    val updatedAt: String
) {
    val isLow: Boolean
        get() = lowStockThreshold != null && quantity <= lowStockThreshold
}

/** Why the stock ledger moved. Every change to a [PantryItem]'s quantity is one [PantryTxn]. */
enum class PantryTxnReason(val value: String, val label: String) {
    IMPORT("import", "Imported"),
    CONSUME("consume", "Used in a meal"),
    MANUAL("manual", "Manual change"),
    CORRECTION("correction", "Correction"),
    // Stock that came back from a grocery run — the checked-off list items purchased and shelved.
    // Distinct from IMPORT (a parsed order document) so the ledger reads "Restocked" for a shop.
    RESTOCK("restock", "Restocked"),
    // A repackage: the same physical stock re-expressed at a finer granularity ("2 lb" → "3 meals",
    // "1 unit of 58-count" → "58 pieces"). Not an add or a use — the quantity/unit just change shape.
    SPLIT("split", "Broke into pieces");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: MANUAL
    }
}

/**
 * A single movement of stock. [delta] is signed: positive when stock was added (an import, a manual
 * top-up), negative when consumed. Consumption groups by [mealName] (and optionally [recipeId]) so
 * "for X meal, here's what I used" is a first-class, queryable record — a shopping-and-cooking
 * ledger, the same "keep the receipts" spirit as the rest of the suite.
 */
data class PantryTxn(
    val id: String,
    val pantryItemId: String,
    val delta: Double,
    val unit: String,
    val reason: PantryTxnReason,
    val mealName: String? = null,
    val recipeId: String? = null,
    val importBatchId: String? = null,
    // Groups every CONSUME row that came from a single "log a meal" action, so history can rebuild —
    // and re-run — that one meal exactly. Null for non-meal movements and legacy pre-migration rows.
    val mealLogId: String? = null,
    val note: String? = null,
    val createdAt: String
)

/**
 * One past meal, reconstructed from the CONSUME ledger — the unit the History screen shows and the
 * "Log again" button replays. [lines] are the pantry deductions that made up the meal; [loggedAt] is
 * when it was logged.
 */
data class MealLog(
    val id: String,
    val mealName: String,
    val recipeId: String?,
    val loggedAt: String,
    val lines: List<MealLine>
) {
    val itemCount: Int get() = lines.size
}

/** One deducted item within a [MealLog]. [pantryItemId] is the row to deduct from again on a remake;
 *  [available] is false when that row no longer exists so the UI can flag a partial remake. */
data class MealLine(
    val pantryItemId: String,
    val name: String,
    val amount: Double,
    val unit: String,
    val available: Boolean = true
)

/** How an [ImportBatch] came in — a real Walmart PDF, or pasted/shared order text. */
enum class ImportSource(val value: String, val label: String) {
    WALMART_PDF("walmart_pdf", "Walmart PDF"),
    PASTED_TEXT("pasted_text", "Pasted text");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: PASTED_TEXT
    }
}

/** Provenance for one import run: where it came from, the order number if we found one, and how
 *  many lines landed. Kept so the pantry can show "added 84 items from Walmart order #… on …". */
data class ImportBatch(
    val id: String,
    val source: ImportSource,
    val orderNumber: String? = null,
    val itemCount: Int,
    val label: String,
    val createdAt: String
)

/**
 * A framework-free result of parsing one order line, before it touches the database. The import
 * preview screen shows these; confirming turns each into a [PantryItem] (+ an IMPORT [PantryTxn]).
 */
data class ParsedOrderLine(
    val rawName: String,
    val quantity: Int,
    val unit: String,
    val category: String?,
    val priceCents: Int? = null
)

/** The whole parse of an order document/text: its lines plus any order number we could recover. */
data class ParsedOrder(
    val orderNumber: String?,
    val lines: List<ParsedOrderLine>
)

/**
 * A framework-free result of parsing a recipe web page (schema.org Recipe JSON-LD / microdata).
 * Ingredients are raw human strings ("2 cups all-purpose flour"); [IngredientLineParser] splits each
 * into quantity/unit/name when the recipe is materialized into LifeOps.
 */
data class ParsedRecipe(
    val name: String,
    val servings: Double?,
    val ingredients: List<String>,
    val sourceUrl: String? = null,
    /** The method, one step per entry. Empty when the page published no instructions — plenty of
     *  sites emit only `recipeIngredient`, and a recipe with no steps is still worth importing. */
    val steps: List<String> = emptyList()
)

/** One recipe ingredient line broken into parts. Any field may be blank/absent when the line is
 *  free-form ("salt to taste"); the name always survives. */
data class ParsedIngredient(
    val quantity: Double?,
    val unit: String?,
    val name: String,
    val raw: String
)

/** Where a [GroceryItem] came onto the list — so the list can explain itself ("added because you're
 *  low", "needed for Chili night") and so re-running a source doesn't duplicate what's already there. */
enum class GrocerySource(val value: String, val label: String) {
    MANUAL("manual", "Added by hand"),
    LOW_STOCK("low_stock", "Running low"),
    RECIPE("recipe", "For a recipe");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: MANUAL
    }
}

/**
 * One line on the grocery/shopping list — the thing Logistics is a "list builder" for. A line can be
 * added by hand, pulled in because a pantry row dropped below its alert level, or gathered from a
 * recipe's missing ingredients. [foodItemId] links to a LifeOps catalog food when known, so checking
 * the item off restocks the right pantry row (matched by id first, then name). [checked] is the
 * shopping-cart tick; checked lines are what "Add to pantry" purchases and clears.
 */
data class GroceryItem(
    val id: String,
    val foodItemId: String? = null,
    val name: String,
    val quantity: Double,
    val unit: String = "unit",
    val category: String? = null,
    val source: GrocerySource = GrocerySource.MANUAL,
    val recipeId: String? = null,
    val checked: Boolean = false,
    val note: String? = null,
    val createdAt: String,
    val updatedAt: String
)

/**
 * One resolved ingredient line of a LifeOps recipe, ready to show. [gapLabel] is why the line adds
 * nothing to the recipe's macros when it doesn't ("macros not recorded", "food no longer in the
 * catalog") — the same reason LifeOps prints, carried across rather than recomputed, so a recipe
 * imported from a link or a screenshot reads as a floor rather than a fiction.
 */
data class IngredientRow(
    val id: String,
    val foodName: String,
    val quantity: Double,
    val unit: String,
    val gapLabel: String? = null
)

/**
 * A screenshot kept with a recipe — the picture the recipe was read out of, or one added to it
 * afterwards. Plenty of recipes only ever exist as a photo of a card, a story slide, or a page of a
 * cookbook; the OCR turns that into ingredients you can shop and cook from, and the picture stays so
 * you can check the parse against the original.
 *
 * [recipeId] points at a LifeOps recipe the same soft way [PantryItem.foodItemId] points at a food —
 * two databases in one process, so it is a plain id resolved at read time, not a foreign key.
 * [fileName] names a JPEG in Logistics' own `recipe-shots/` directory; the bytes never go in the
 * database (see `data/store/RecipeShotStore`).
 */
data class RecipeShot(
    val id: String,
    val recipeId: String,
    val fileName: String,
    val sortOrder: Int = 0,
    val createdAt: String
)
