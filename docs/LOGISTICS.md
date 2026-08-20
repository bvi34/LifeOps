# Logistics — the pantry & inventory app

Logistics is the suite's **kitchen operations** app: a virtual pantry you fill from a grocery order,
draw down as you cook, and reconcile against the meals you actually made. It is a hosted library
module inside the Operations Sandbox container (`:app`), a peer to LifeOps and Citation — opened from
the sandbox home, backed up into the same one-zip archive.

Its guiding idea is the same "keep the receipts" spirit as the rest of the suite: what you bought,
what's left, and what went into each meal is a **ledger**, not a vibe.

## What it does

| Tab | Purpose |
|---|---|
| **Pantry** | The shelf. Every stock line with its quantity, unit and category; quick +/- adjustments, low-stock flags, add-by-hand, **Break into pieces** to re-express one line at a finer granularity, and — tap a row — **set its exact amount and a low-stock alert level**. |
| **Grocery** | The shopping list. Add items by hand, pull in everything **running low** in one tap, or gather a recipe's **missing ingredients**; check things off as you shop, then **Add to pantry** shelves the checked lines (a `restock` ledger entry each) and clears them. |
| **Log meal** | "For *X* meal, here's what I used." **Search** the shelf to grab specific items, name a meal (optionally from a LifeOps recipe), mark what you took, and Logistics deducts it from the pantry. |
| **History** | Every past meal, newest first, with the items it drew down — **Make again** re-deducts the same items in one tap. |
| **Recipes** | Grab a recipe from any link (schema.org data) into **LifeOps'** recipe book, and browse the recipes already there. |
| **Import** | Fill the pantry from a **Walmart order** — pick the order's PDF, share it to Logistics, or paste its text, review the parsed lines, confirm. |

## How it relates to LifeOps

Logistics does **not** keep its own copy of foods and recipes. It depends on the `:lifeops` module
and reads/writes LifeOps' existing `food_items` / `recipes` catalog through
`LifeOpsCatalog` (which wraps LifeOps' own `FoodItemRepository` / `RecipeRepository` over the shared
`LifeOpsDatabase`). So the food and recipe data is **one catalog, shared by both apps** — exactly
the "take the food, recipes, etc. from LifeOps" ask.

Logistics owns only what LifeOps doesn't, in its own `logistics.db`:

- **`pantry_items`** — stock lines (quantity + free-form packaging unit + category), each optionally
  linked to a LifeOps food by id (a soft reference, not a cross-database FK).
- **`pantry_txns`** — the movement ledger. Every quantity change is one signed row: `import`,
  `consume` (stamped with the meal name / recipe, and a `mealLogId` grouping one meal's rows so
  History can replay it), `manual`, `correction`, `split` (a repackage), or `restock` (bought off
  the grocery list). *Schema v2 adds `mealLogId`; a manual `MIGRATION_1_2` backfills it as `NULL`.*
- **`import_batches`** — provenance for each import run (source, order number, item count).
- **`grocery_items`** — the shopping list: name, quantity, unit, category, a nullable LifeOps food
  link, a `source` (`manual` / `low_stock` / `recipe`), and a `checked` tick. *Schema v3 adds this
  table via `MIGRATION_2_3`.*

## Module layout

```
:logistics (Android library, com.logistics.app)
├── logic/            pure JVM, unit-tested — no Android imports
│   ├── WalmartOrderParser   extracted order text → structured lines
│   ├── PantryUnits          product name → packaging unit + aisle category
│   ├── GroceryPlanner       restock-quantity + missing-ingredient rules for the list
│   ├── IngredientLineParser "2 cups flour" → {qty, unit, name}
│   └── RecipeLinkParser     page HTML → schema.org Recipe (JSON-LD + microdata)
├── net/              the only Android/IO shims
│   ├── PdfTextExtractor     PDFBox-Android: PDF → text (feeds WalmartOrderParser)
│   └── RecipeFetcher        HttpURLConnection: URL → HTML (feeds RecipeLinkParser)
├── data/             Room (LogisticsDatabase, entities, PantryDao) + repositories
│   └── repository/   PantryRepository (pantry + ledger + import + consume + grocery) · LifeOpsCatalog (bridge)
├── ui/               Compose: pantry · grocery · importflow · meal · history · recipe (+ theme)
├── backup/           LogisticsBackupContributor (whole-file logistics.db copy)
├── LogisticsApp.kt   tiny runtime container (install/get), like LifeOpsApp
└── MainActivity.kt   tabbed shell; also handles SEND pdf|text|link intents
```

The split mirrors LifeOps' growth/weather approach: **everything that can be pure logic is**, so the
parsers are provable on the JVM without a device.

## The Walmart import — why the parser is text-first

A Walmart "Order details" PDF encodes its text with **subsetted, glyph-remapped fonts**, so naive
extraction yields font tables, not words. `PdfTextExtractor` uses PDFBox-Android (which resolves the
page's `ToUnicode` maps) to recover the real text, then hands it to the framework-free
`WalmartOrderParser`. That extracted text runs every row together with a `Qty n$price` stamp, e.g.

```
…Order# 2000151-51271198Malt-O-Meal S'mores…, 47 oz BagQty 1$7.83Hefty…Qty 2$6.56…
```

so the parser splits on that stamp: the product name is everything since the previous stamp (or the
`Order#` header, for the first item). The `Subtotal/Tax/Total` footer carries no `Qty` stamp and is
excluded for free. Two edge cases from the real file are pinned by tests: the header running
straight into the first product name (`…51271198Malt-O-Meal…`) and product sizes like `47 oz` that
must survive name cleaning. A **paste-text** path uses the identical parser, so an order copied from
the web works without the PDF at all.

## Consumption — "here's what I used"

`PantryRepository.consumeMeal(mealName, recipeId?, consumptions)` deducts each chosen pantry item
(clamped so stock never goes negative) and writes a `consume` ledger row stamped with the meal name,
a shared `mealLogId` for the whole action, and — when you cooked a known recipe — its LifeOps
`recipeId`. Picking a LifeOps recipe in the Log-meal tab **auto-selects** the pantry lines whose
linked food is one of that recipe's ingredients, so cooking a saved recipe is a two-tap deduct. A
**Search** box filters the shelf so you can pull specific items into a meal without scrolling; marked
items stay visible as you refine the query.

## History — remake a meal

`PantryRepository.observeMealHistory()` rolls the `consume` ledger back up into `MealLog`s (grouped by
`mealLogId`; pre-v2 rows fall back to meal name + timestamp) and resolves each line's name/unit against
the live shelf. The **History** tab lists them newest-first; **Make again** calls
`remakeMeal(mealLog)`, which re-runs `consumeMeal` with the same lines — re-deducting what's on hand
(clamped) and landing a fresh meal in the ledger. Lines whose pantry row is gone are skipped and
flagged in the list.

## Break into pieces — repackaging a unit

`PantryRepository.splitItem(itemId, pieces, pieceUnit)` re-expresses one stock line at a finer
granularity: the same physical stock, a new count and unit — **2 lb → 3 meals**, **1 unit of a
58-count box → 58 pieces**. It overwrites the line's quantity/unit and lands a `split` ledger row
noting the before/after (`PantryUnits.splitNote`, JVM-tested), so the shelf stays auditable. The
Pantry row's **Break into pieces** action opens a dialog with a live `before → after` preview.

## Grocery list — build it, shop it, shelve it

The **Grocery** tab is a first-class shopping list wired into both ends of the pantry. Three ways
fill it, all de-duplicated by name (`PantryRepository.addGroceryItem` tops up an existing line and
un-checks it rather than duplicating):

- **By hand** — a name/qty/unit/category dialog.
- **Restock low** — `addLowStockToGrocery()` sweeps every pantry line at or below its alert level (or
  simply out of stock) and adds it, with a suggested count from `GroceryPlanner.restockQuantity`
  (round the deficit up to whole units; at least one). The alert level is set by tapping a Pantry row.
- **From recipe** — `addRecipeMissingToGrocery(recipeId)` resolves the recipe's foods
  (`LifeOpsCatalog.ingredientFoods`) and adds only the ones you don't already have, matched by catalog
  id first then name via `GroceryPlanner.missingIngredients` (both JVM-tested).

As you shop you check items off (they strike through and sink to the bottom). **Add to pantry** —
`purchaseCheckedIntoPantry()` — closes the loop shop → shelf: each checked line tops up its matching
pantry row (by food id, then name) or creates a new one, lands a `restock` ledger entry, and is
removed from the list. So a grocery run is as auditable as an import or a meal.

## Recipe-from-link

`RecipeFetcher` fetches the page; `RecipeLinkParser` reads its **schema.org/Recipe** data
(`application/ld+json`, including inside an `@graph`, with a microdata fallback) into a name,
servings, and ingredient lines. Saving materializes it as a **LifeOps recipe**: each ingredient line
is split by `IngredientLineParser`, matched to (or created as) a LifeOps custom food, and attached.
Imported units (cups, tbsp) don't fit LifeOps' strict GRAM/SERVING ingredient math, so the parsed
unit is preserved on the food's serving unit and the ingredient is stored as a SERVING quantity —
the ingredient list stays faithful even when macros are unknown.

## Backup

`LogisticsBackupContributor` (registered as `AppId.LOGISTICS`) copies the whole `logistics.db` into
the sandbox archive and swaps it back on restore — complete by construction, the same approach
LifeOps uses. Foods and recipes are LifeOps' data and are backed up by LifeOps' contributor, so
Logistics never double-stores them.

## Tests

Pure-JVM suites under `logistics/src/test` (run with `gradle :logistics:testDebugUnitTest`):

- `WalmartOrderParserTest` — order-number recovery, item count, the run-together first item, qty/price,
  unit/category inference, footer exclusion, non-order text.
- `PantryUnitsTest` — packaging-word choice (incl. "Canister" over "Can"), `each` normalization,
  keyword categories, the tightened rule that keeps *fresh* meat out of Produce, and the
  `splitNote` before/after copy used by **Break into pieces**.
- `GroceryPlannerTest` — restock-quantity rounding (deficit up to whole units, at least one, one when
  no threshold) and missing-ingredient matching (by id and by case-insensitive name, de-duplicated).
- `IngredientLineParserTest` — quantities, fractions (`1/2`, `1 1/2`, `½`), unit vs. size words,
  free-form lines.
- `RecipeLinkParserTest` — JSON-LD, `@graph`, HTML-entity decoding, and the microdata fallback.
