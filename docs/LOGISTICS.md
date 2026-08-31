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
| **Meal** | "For *X* meal, here's what I used." **Search** the shelf to grab specific items, name a meal (optionally from a LifeOps recipe), mark what you took, and Logistics deducts it from the pantry — and, when the recipe knows its macros, **logs the calories to the food diary in the same tap**. |
| **Food** | The **food diary and its calories** — LifeOps' own, opened here. A day at a time: what's logged, what's planned, the day's kcal/macros against your 7-day average, Confirm/Adjust, ad-hoc entries, "save as a food", and planning a recipe onto a day. |
| **History** | Every past meal, newest first, with the items it drew down — **Make again** re-deducts the same items in one tap. |
| **Recipes** | Grab a recipe from any link (schema.org data) **or from screenshots of one** (on-device OCR), correct the parse, and keep it in **LifeOps'** recipe book — with the screenshots attached. Tap a recipe to see its calories per serving, its ingredient lines and the pictures it came from — and to write **notes and reviews** against it: how it turned out, what to change next time, an optional 1–5 star verdict. The notes are Logistics', kept *beside* the recipe and never folded into it. |
| **Import** | Fill the pantry from a **Walmart order** — pick the order's PDF, share it to Logistics, or paste its text, review the parsed lines, confirm. |

## How it relates to LifeOps

Logistics does **not** keep its own copy of foods and recipes. It depends on the `:lifeops` module
and reads/writes LifeOps' existing `food_items` / `recipes` catalog through
`LifeOpsCatalog` (which wraps LifeOps' own `FoodItemRepository` / `RecipeRepository` over the shared
`LifeOpsDatabase`). So the food and recipe data is **one catalog, shared by both apps** — exactly
the "take the food, recipes, etc. from LifeOps" ask.

The same rule now covers **the food diary and its calories**. Logistics' **Food** tab is not a second
diary beside LifeOps' — it *is* LifeOps' diary: `LifeOpsCatalog` wraps LifeOps' own `FoodService` and
`MealPlanService`, so logging a bowl of chili here writes the row LifeOps' Daily Plan shows, planning
a recipe here lands on the same `weekly_menu_items` week, and confirming it in either app confirms it
in both. The calorie maths is LifeOps' `NutritionCalculator`, imported rather than re-derived — which
is also why a recipe whose ingredients carry no macros reads as a **floor** ("at least 312 kcal — 4 of
9 ingredients have no macros recorded") rather than a confident number.

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
- **`recipe_shots`** — the screenshots kept with a recipe: a soft `recipeId` into LifeOps' book, the
  JPEG's **file name**, and a sort order (a recipe rarely fits on one screen). The bytes live in
  `filesDir/recipe-shots/`, not in the database — see below. *Schema v4 adds this table via
  `MIGRATION_3_4`.*
- **`recipe_notes`** — what somebody thought of a recipe *after cooking it*: the same soft
  `recipeId`, a nullable 1–5 `rating`, the note's `text`, and created/updated stamps. Kept beside the
  recipe rather than in it — see [Notes and reviews](#notes-and-reviews--kept-beside-the-recipe).
  *Schema v5 adds this table via `MIGRATION_4_5`.*

## Module layout

```
:logistics (Android library, com.logistics.app)
├── logic/            pure JVM, unit-tested — no Android imports
│   ├── WalmartOrderParser   extracted order text → structured lines
│   ├── PantryUnits          product name → packaging unit + aisle category
│   ├── GroceryPlanner       restock-quantity + missing-ingredient rules for the list
│   ├── IngredientLineParser "2 cups flour" → {qty, unit, name}
│   ├── RecipeLinkParser     page HTML → schema.org Recipe (JSON-LD + microdata)
│   ├── RecipeTextParser     OCR'd screenshot text → the same ParsedRecipe, by layout
│   └── RecipeNoteSummaries  a recipe's notes → the count + average rating a row shows
├── net/              the only Android/IO shims
│   ├── PdfTextExtractor      PDFBox-Android: PDF → text (feeds WalmartOrderParser)
│   ├── RecipeFetcher         HttpURLConnection: URL → HTML (feeds RecipeLinkParser)
│   └── RecipeScreenshotReader ML Kit (bundled, on-device): picture → text (feeds RecipeTextParser)
├── data/             Room (LogisticsDatabase, entities, PantryDao, RecipeShotDao, RecipeNoteDao) + repositories
│   ├── repository/   PantryRepository (pantry + ledger + import + consume + grocery) · LifeOpsCatalog
│   │                 (bridge: foods, recipes, and LifeOps' food diary) · RecipeShotRepository ·
│   │                 RecipeNoteRepository
│   └── store/        RecipeShotStore — the screenshots on disk, downsampled, out of the database
├── ui/               Compose: pantry · grocery · importflow · meal · food · history · recipe (+ theme)
├── backup/           LogisticsBackupContributor (whole-file logistics.db copy + the screenshots)
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
servings, ingredient lines, and the **method**. `recipeInstructions` is the least standardised field
in the spec — a string, an array of strings, `HowToStep` objects, or `HowToSection`s wrapping their
own steps — and all four shapes flatten to one plain-text step list.

Saving materializes it as a **LifeOps recipe**: each ingredient line is split by
`IngredientLineParser`, matched to (or created as) a LifeOps custom food, and attached; the steps
land in the recipe's `instructions` and the page URL in its `sourceUrl`, so the import is something
you can cook from and trace back rather than a shopping list with a name on it. Imported units
(cups, tbsp) don't fit LifeOps' strict GRAM/SERVING ingredient math, so the parsed unit is preserved
on the food's serving unit and the ingredient is stored as a SERVING quantity — the ingredient list
stays faithful even when macros are unknown, and LifeOps' recipe screen flags those lines instead of
summing them as zero.

## Recipe-from-screenshot

Plenty of the recipes people actually cook never had a page to link: a photograph of a handwritten
card, a story slide, a page of a cookbook, a text from a relative. Screenshotting one is the gesture
people already make, so the Recipes tab takes screenshots as a first-class import — picked from the
gallery, or **shared straight into Logistics** (`image/*` on `SEND` and `SEND_MULTIPLE`, which opens
the Recipes tab with the OCR already running).

`RecipeScreenshotReader` recognises the text on-device with ML Kit's **bundled** Latin recogniser —
the model ships inside the app, so it needs no network, no Play Services download and no account, and
nothing about the picture leaves the phone. Several pictures are read as **one page**, in order, since
a recipe is normally two or three shots (ingredients, then method).

The layout reading is the framework-free `RecipeTextParser`, and it is a *layout* reader rather than a
format reader: it finds the "Ingredients" and "Directions"/"Method" headings that nearly every recipe
prints, takes the title from the block above them, reads `Serves 4` / `Makes 12` / `4 servings`
wherever it appears, and drops the page furniture (prep times, ratings, *Print*/*Save*/*Jump to
recipe*). A crop with no headings at all is read by its quantities — every line that opens with an
amount is an ingredient, which is exactly what those crops look like. It is deliberately forgiving:
an unclassifiable line lands in the ingredient list rather than being dropped, because a wrong line
you can delete beats a missing one you have to notice.

Then the parse is **shown for correction before it is kept** — name, servings, ingredients and method,
one per line, all editable — because OCR is a reading of a picture and not a fact. Saving materializes
it as a LifeOps recipe exactly like a link import does, and **attaches the screenshots to it**, so the
original is always there to check the parse against. The same "Add a screenshot" action hangs off any
recipe already in the book.

## Notes and reviews — kept beside the recipe

A recipe in LifeOps' book is shared by the whole suite: the meal planner schedules it, the diary logs
its calories, the grocery list shops its ingredients. So what one cook thought of it on one evening —
*"halve the salt", "needed 10 more minutes", four stars* — has no business being appended to the
method. Logistics keeps that in its **own** table, `recipe_notes`, on the same soft `recipeId` the
screenshots use, and the recipe is never written to.

Tap a recipe open and the **Notes & reviews** section sits under its ingredients and pictures. A note
is a paragraph, a 1–5 star rating, or both — either half alone is enough to keep, and one with
neither is refused rather than stored empty. Notes read newest-first, since the last time you made
something is the time that matters; each one can be rewritten in place (it keeps its position in the
log and is marked *edited*) or deleted, which asks first because the words were typed rather than
picked.

**A rating is optional, and that is load-bearing.** Most cooking notes carry no verdict, so `rating`
is nullable and `RecipeNoteSummaries` averages only the notes that have one — three notes and one
five-star review reads as *★ 5 · 3 notes*, not 1.7 — and the section says what the average is *of*
("5 from 1 rating"), so a well-documented recipe with a single opinion doesn't masquerade as a
consensus. Ratings outside 1–5 are ignored rather than trusted, and a cleared rating means "no
verdict", never one star.

The notes travel with the rest of Logistics' data: they are rows in `logistics.db`, which the backup
copies whole.

## The Food tab — the diary, in the app where the food is

The **Food** tab is LifeOps' food-and-calorie surface, opened beside the shelf it came off: a day at a
time, with the day's chips across the top and the week steppable either side.

- **The day's totals**, logged and planned side by side (kcal, carbs, protein, fat), over a **7-day
  per-day average** — totals ÷ the days actually logged, so a week with three logged days averages
  over three rather than reading as a third of a week.
- **Log food** — search the catalog (or take a **recent/frequent** suggestion, which is most real
  logging), in **servings or grams**, or enter something by hand with its macros. LifeOps' maths is
  strict on purpose: a quantity is either servings of the food or grams of it, never a guessed
  cup-to-gram conversion, so a gram entry against a food with no known serving weight is refused with
  a reason rather than silently zeroed.
- **Confirm / Adjust** on every entry, and **"Save as food"** to promote a one-off entry into a
  permanent catalog food — type "my protein shake" once, find it forever.
- **Plan a meal from a recipe** onto the day, which writes the menu line and the planned (unconfirmed)
  diary entry carrying the recipe's macros for the servings planned; unplanning takes it back off
  unless it has already been confirmed, at which point it records what was actually eaten.

And the join the two halves of the app were always missing: picking a known recipe in **Meal** now
offers "**log it in the food diary too**", with the kcal it would add, so deducting a cooked recipe
from the pantry and recording what you ate is one action instead of two. It is offered only when the
recipe knows its macros — an import whose ingredients are all placeholders has nothing honest to log.

## Backup

`LogisticsBackupContributor` (registered as `AppId.LOGISTICS`) copies the whole `logistics.db` into
the sandbox archive and swaps it back on restore — complete by construction, the same approach
LifeOps uses. Foods, recipes and diary entries are LifeOps' data and are backed up by LifeOps'
contributor, so Logistics never double-stores them.

The **recipe screenshots go with it**, entry by entry, because they are Logistics' own and live
outside the database: the row keeps a file name and the JPEG sits in `filesDir/recipe-shots/`. A
backup that carried the row and not the picture would restore a recipe that claims a screenshot and
hasn't got one — worse than not backing it up at all, since the app would look like it had it. On
restore the files are written **before** the database that names them, and an archive entry that
tries to name a path outside the directory is refused. It is the same shape as Health's insurance
cards and paperwork.

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
- `RecipeNoteSummariesTest` — the notes summary: unrated notes never average in as zero, ratings
  outside 1–5 are ignored, whole stars print without a decimal, and an unrated recipe reads as a
  count rather than a score.
- `RecipeNoteRepositoryTest` — notes against a recipe (over a fake DAO): text is trimmed, a note with
  neither words nor stars is refused, stars alone are keepable, ratings clamp into 1–5 with zero
  meaning "no verdict", an edit keeps `createdAt` and won't silently empty a note out, and sweeping
  one recipe's notes leaves another's alone.
- `RecipeLinkParserTest` — JSON-LD, `@graph`, HTML-entity decoding, the microdata fallback, and the
  four shapes `recipeInstructions` arrives in (steps, sections, one blob, none).
- `RecipeTextParserTest` — the screenshot layout reader: title/servings/ingredients/method off a
  recipe card, page furniture (times, *Print*, a nutrition line) dropped, bullets and checkboxes
  stripped, an unlabelled crop read by its quantities, `Serves`/`Makes`/`4 servings` phrasing, step
  numbering removed, the seam between two overlapping screenshots collapsed, and empty text parsing
  to an empty recipe rather than throwing.
