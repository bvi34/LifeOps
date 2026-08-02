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
    CORRECTION("correction", "Correction");

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
    val note: String? = null,
    val createdAt: String
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
    val sourceUrl: String? = null
)

/** One recipe ingredient line broken into parts. Any field may be blank/absent when the line is
 *  free-form ("salt to taste"); the name always survives. */
data class ParsedIngredient(
    val quantity: Double?,
    val unit: String?,
    val name: String,
    val raw: String
)
