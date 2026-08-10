package com.advisor.app.logic

/**
 * Pulls the **structured fields** back out of a [KnowledgeDocument]'s body — stock quantities, task
 * status, estimate minutes, milestone points, and so on. The knowledge sources (`data/source/`) flatten
 * each app row into a fixed, machine-generated sentence ("… In stock: 2 Count. Category: Frozen …"), so
 * a small set of anchored patterns recovers the numbers and labels reliably. Centralising the parsing
 * here means the coupling to that phrasing lives in exactly one place (and is unit-tested), so the
 * inventory and calculator functions can reason over typed values instead of re-parsing prose.
 */
object DocumentFacts {

    private val STOCK = Regex("""In stock:\s*(-?\d+(?:\.\d+)?)\s*([^.]*)""", RegexOption.IGNORE_CASE)
    private val GROCERY = Regex(
        """Grocery list item:.*?,\s*(-?\d+(?:\.\d+)?)\s*(.*?)\s*\((needed|bought)\)""",
        RegexOption.IGNORE_CASE
    )
    private val CATEGORY = Regex("""Category:\s*([^.]+)""", RegexOption.IGNORE_CASE)
    private val ESTIMATE = Regex("""Estimate:\s*(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val POINTS = Regex("""\((\d+)\s*pts\)""", RegexOption.IGNORE_CASE)
    private val STATUS = Regex("""Status:\s*([^.]+)""", RegexOption.IGNORE_CASE)
    private val PRIORITY = Regex("""Priority:\s*([^.]+)""", RegexOption.IGNORE_CASE)
    private val READING_STATE = Regex("""Reading state:\s*([A-Za-z_]+)""", RegexOption.IGNORE_CASE)
    private val BOOK_AUTHOR = Regex("""Book:.*?\bby\s+(.+?)\.\s*Source:""", RegexOption.IGNORE_CASE)

    /** Pantry stock quantity, e.g. 2.0 from "In stock: 2 Count". */
    fun stockQuantity(doc: KnowledgeDocument): Double? =
        STOCK.find(doc.body)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Pantry stock unit, e.g. "Count" or "hot dogs". */
    fun stockUnit(doc: KnowledgeDocument): String? =
        STOCK.find(doc.body)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }

    /** True when the pantry item is flagged as running low. */
    fun isLowStock(doc: KnowledgeDocument): Boolean =
        doc.body.contains("Running low", ignoreCase = true)

    /** Grocery quantity, e.g. 1.0 from "…, 1 loaf (needed)". */
    fun groceryQuantity(doc: KnowledgeDocument): Double? =
        GROCERY.find(doc.body)?.groupValues?.get(1)?.toDoubleOrNull()

    /** True when a grocery line is still needed (not yet bought). */
    fun groceryNeeded(doc: KnowledgeDocument): Boolean =
        doc.body.contains("(needed)", ignoreCase = true)

    /** The item's category, when one is recorded. */
    fun category(doc: KnowledgeDocument): String? =
        CATEGORY.find(doc.body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** A task's estimate in minutes, when set. */
    fun estimateMinutes(doc: KnowledgeDocument): Int? =
        ESTIMATE.find(doc.body)?.groupValues?.get(1)?.toIntOrNull()

    /** A milestone's points, e.g. 5 from "(5 pts)". */
    fun points(doc: KnowledgeDocument): Int? =
        POINTS.find(doc.body)?.groupValues?.get(1)?.toIntOrNull()

    /** A task's/project's status label, e.g. "done". */
    fun status(doc: KnowledgeDocument): String? =
        STATUS.find(doc.body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** A task's priority label, e.g. "high". */
    fun priority(doc: KnowledgeDocument): String? =
        PRIORITY.find(doc.body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** A book's reading state, normalized to upper case: "READING", "TO_READ", or "DONE". */
    fun readingState(doc: KnowledgeDocument): String? =
        READING_STATE.find(doc.body)?.groupValues?.get(1)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

    /** A book's author, e.g. "Michael W Lucas" from "Book: … by Michael W Lucas. Source: …". */
    fun author(doc: KnowledgeDocument): String? =
        BOOK_AUTHOR.find(doc.body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
}
