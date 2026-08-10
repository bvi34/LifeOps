package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses the exact prose the knowledge sources emit, so the fact extraction is pinned to real input. */
class DocumentFactsTest {

    private fun doc(kind: String, title: String, body: String) =
        KnowledgeDocument("lifeops:$kind:1", SourceApp.LIFEOPS, kind, title, body)

    @Test
    fun reads_pantry_stock_unit_category_and_low_flag() {
        val d = doc(
            "pantry", "Hot Dogs",
            "Pantry item: Hot Dogs. In stock: 8 hot dogs. Category: Meat & Seafood. Running low."
        )
        assertEquals(8.0, DocumentFacts.stockQuantity(d)!!, 0.0)
        assertEquals("hot dogs", DocumentFacts.stockUnit(d))
        assertEquals("Meat & Seafood", DocumentFacts.category(d))
        assertTrue(DocumentFacts.isLowStock(d))
    }

    @Test
    fun a_well_stocked_item_is_not_low() {
        val d = doc("pantry", "Flour", "Pantry item: Flour. In stock: 2.5 kg. Category: Baking")
        assertEquals(2.5, DocumentFacts.stockQuantity(d)!!, 0.0)
        assertEquals("kg", DocumentFacts.stockUnit(d))
        assertFalse(DocumentFacts.isLowStock(d))
    }

    @Test
    fun reads_grocery_quantity_and_needed_flag() {
        val d = doc("grocery", "Milk", "Grocery list item: Milk, 1 gallon (needed). Category: Dairy")
        assertEquals(1.0, DocumentFacts.groceryQuantity(d)!!, 0.0)
        assertTrue(DocumentFacts.groceryNeeded(d))
    }

    @Test
    fun reads_task_status_priority_and_estimate() {
        val d = doc(
            "task", "Build report",
            "Task: Build report. Status: done. Priority: high. Aspect: Work. Estimate: 30 min. Completed: 2026-08-10"
        )
        assertEquals("done", DocumentFacts.status(d))
        assertEquals("high", DocumentFacts.priority(d))
        assertEquals(30, DocumentFacts.estimateMinutes(d))
    }

    @Test
    fun reads_milestone_points() {
        val d = doc("milestone", "First 5k", "Milestone: First 5k (5 pts). Achieved: 2026-01-01. Aspect: Health")
        assertEquals(5, DocumentFacts.points(d))
    }

    @Test
    fun missing_fields_are_null() {
        val d = doc("task", "No estimate", "Task: No estimate. Status: todo. Priority: low")
        assertNull(DocumentFacts.estimateMinutes(d))
        assertNull(DocumentFacts.points(d))
    }
}
