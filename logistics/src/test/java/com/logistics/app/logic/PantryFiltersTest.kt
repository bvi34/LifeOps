package com.logistics.app.logic

import com.logistics.app.data.model.PantryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PantryFiltersTest {

    private fun item(id: String, quantity: Double) =
        PantryItem(id = id, name = id, quantity = quantity, createdAt = "t", updatedAt = "t")

    @Test
    fun hidesUsedUpLines() {
        val items = listOf(item("milk", 1.0), item("rice", 0.0), item("beans", 2.0))
        assertEquals(listOf("milk", "beans"), PantryFilters.hideEmpty(items, hide = true).map { it.id })
    }

    @Test
    fun showsEverythingWhenOff() {
        val items = listOf(item("milk", 1.0), item("rice", 0.0))
        assertEquals(items, PantryFilters.hideEmpty(items, hide = false))
    }

    @Test
    fun keepsMarkedItemsVisible() {
        // Log meal keeps a ticked (or recipe-matched) line on screen even at zero, so a selection
        // can't vanish out from under you.
        val items = listOf(item("milk", 1.0), item("rice", 0.0))
        assertEquals(
            listOf("milk", "rice"),
            PantryFilters.hideEmpty(items, hide = true, keep = setOf("rice")).map { it.id }
        )
    }

    @Test
    fun treatsNegativeStockAsEmpty() {
        assertTrue(PantryFilters.isEmpty(item("drifted", -0.0001)))
        assertFalse(PantryFilters.isEmpty(item("open bag", 0.25)))
    }

    @Test
    fun countsWhatWouldBeHidden() {
        val items = listOf(item("milk", 1.0), item("rice", 0.0), item("beans", 0.0))
        assertEquals(2, PantryFilters.emptyCount(items))
        assertEquals(0, PantryFilters.emptyCount(emptyList()))
    }
}
