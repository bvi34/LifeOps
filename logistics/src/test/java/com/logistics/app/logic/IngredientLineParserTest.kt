package com.logistics.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientLineParserTest {

    @Test
    fun parsesQuantityUnitAndName() {
        val r = IngredientLineParser.parse("2 cups all-purpose flour")
        assertEquals(2.0, r.quantity!!, 0.0001)
        assertEquals("cups", r.unit)
        assertEquals("all-purpose flour", r.name)
    }

    @Test
    fun parsesSimpleFraction() {
        val r = IngredientLineParser.parse("1/2 teaspoon salt")
        assertEquals(0.5, r.quantity!!, 0.0001)
        assertEquals("teaspoon", r.unit)
        assertEquals("salt", r.name)
    }

    @Test
    fun parsesMixedNumber() {
        val r = IngredientLineParser.parse("1 1/2 cups sugar")
        assertEquals(1.5, r.quantity!!, 0.0001)
        assertEquals("cups", r.unit)
        assertEquals("sugar", r.name)
    }

    @Test
    fun parsesUnicodeFraction() {
        val r = IngredientLineParser.parse("½ cup butter")
        assertEquals(0.5, r.quantity!!, 0.0001)
        assertEquals("cup", r.unit)
        assertEquals("butter", r.name)
    }

    @Test
    fun sizeWordIsNotAUnit() {
        // "large" describes the egg, not an amount — it stays in the name.
        val r = IngredientLineParser.parse("3 large eggs")
        assertEquals(3.0, r.quantity!!, 0.0001)
        assertNull(r.unit)
        assertEquals("large eggs", r.name)
    }

    @Test
    fun keepsFreeFormLineIntact() {
        val r = IngredientLineParser.parse("Salt to taste")
        assertNull(r.quantity)
        assertNull(r.unit)
        assertEquals("Salt to taste", r.name)
    }
}
