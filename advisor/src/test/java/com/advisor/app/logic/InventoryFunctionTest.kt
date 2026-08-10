package com.advisor.app.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryFunctionTest {

    private val fn = InventoryFunction()

    private fun pantry(id: String, name: String, body: String) =
        KnowledgeDocument("logistics:pantry:$id", SourceApp.LOGISTICS, "pantry", name, body)

    private fun grocery(id: String, name: String, body: String) =
        KnowledgeDocument("logistics:grocery:$id", SourceApp.LOGISTICS, "grocery", name, body)

    private val corpus = listOf(
        pantry("1", "Flour", "Pantry item: Flour. In stock: 2 kg. Category: Baking"),
        pantry("2", "Eggs", "Pantry item: Eggs. In stock: 1 dozen. Category: Dairy & Eggs. Running low."),
        pantry("3", "Rice", "Pantry item: Rice. In stock: 5 kg. Category: Baking"),
        grocery("4", "Milk", "Grocery list item: Milk, 1 gallon (needed). Category: Dairy"),
        grocery("5", "Butter", "Grocery list item: Butter, 2 sticks (bought). Category: Dairy")
    )

    private fun req(q: String, granted: Boolean = true) = FunctionRequest(
        question = q,
        corpus = corpus,
        grantedApps = if (granted) setOf(SourceApp.LOGISTICS) else emptySet()
    )

    @Test
    fun lists_low_stock_items() {
        assertTrue(fn.handles("what's running low?"))
        val text = fn.run(req("what's running low?")).text
        assertTrue(text, text.contains("Eggs"))
        assertFalse(text, text.contains("Flour"))
    }

    @Test
    fun looks_up_a_specific_item() {
        val text = fn.run(req("how much flour do I have?")).text
        assertTrue(text, text.contains("Flour"))
        assertTrue(text, text.contains("2 kg"))
    }

    @Test
    fun a_missing_item_that_is_on_the_list_is_flagged() {
        val text = fn.run(req("do I have milk in stock?")).text
        assertTrue(text, text.contains("grocery list"))
    }

    @Test
    fun summarizes_the_pantry_by_category() {
        assertTrue(fn.handles("what's in my pantry?"))
        val text = fn.run(req("what's in my pantry?")).text
        assertTrue(text, text.contains("3 items"))
        assertTrue(text, text.contains("Baking: 2"))
    }

    @Test
    fun lists_needed_groceries_only() {
        val text = fn.run(req("what do I need to buy?")).text
        assertTrue(text, text.contains("Milk"))
        assertFalse(text, text.contains("Butter")) // already bought
    }

    @Test
    fun gates_on_the_logistics_permission() {
        val text = fn.run(req("what's running low?", granted = false)).text
        assertTrue(text, text.contains("Logistics"))
        assertTrue(text, text.contains("Permissions"))
    }

    @Test
    fun does_not_claim_other_apps_questions() {
        assertFalse(fn.handles("how many tasks do I have?"))
        assertFalse(fn.handles("what books am I reading?"))
    }
}
