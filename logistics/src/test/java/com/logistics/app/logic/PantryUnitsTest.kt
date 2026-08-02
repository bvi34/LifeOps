package com.logistics.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PantryUnitsTest {

    @Test
    fun picksTrailingPackagingWord() {
        assertEquals("Bag", PantryUnits.guessUnit("Malt-O-Meal Cereal, 47 oz Bag"))
        assertEquals("Count", PantryUnits.guessUnit("Sandwich Bags, 100 Count"))
        assertEquals("Jar", PantryUnits.guessUnit("Jif Creamy Peanut Butter, 40 oz Jar"))
    }

    @Test
    fun prefersMoreSpecificPackaging() {
        // "Canister" must win over the substring "Can".
        assertEquals("Canister", PantryUnits.guessUnit("Pringles Chips, 5.5 oz Canister"))
    }

    @Test
    fun normalizesEach() {
        assertEquals("each", PantryUnits.guessUnit("Fresh Banana, Each"))
    }

    @Test
    fun fallsBackToUnit() {
        assertEquals("unit", PantryUnits.guessUnit("Something with no packaging word"))
    }

    @Test
    fun categorizesByKeyword() {
        assertEquals("Household", PantryUnits.guessCategory("Hefty Sandwich Bags, 100 Count"))
        assertEquals("Beverages", PantryUnits.guessCategory("A&W Root Beer Soda Pop"))
        assertEquals("Produce", PantryUnits.guessCategory("Fresh Pink Lady Apples, 3lb Bag"))
        assertEquals("Pet", PantryUnits.guessCategory("Purina Cat Chow Indoor Dry Cat Food"))
    }

    @Test
    fun freshMeatIsNotMisfiledAsProduce() {
        // A tightened rule: "Fresh" in a meat name must not pull it into Produce.
        assertEquals("Meat & Seafood", PantryUnits.guessCategory("Prairie Fresh Natural Pork Half Loin, Boneless"))
    }

    @Test
    fun unknownCategoryIsNull() {
        assertNull(PantryUnits.guessCategory("Mystery widget"))
    }

    @Test
    fun splitNoteReadsBeforeAndAfter() {
        assertEquals("Broke 2 lb into 3 meal", PantryUnits.splitNote(2.0, "lb", 3.0, "meal"))
        assertEquals("Broke 1 unit into 58 piece", PantryUnits.splitNote(1.0, "unit", 58.0, "piece"))
    }

    @Test
    fun splitNoteTrimsTrailingZerosButKeepsFractions() {
        assertEquals("Broke 1.5 lb into 4 portion", PantryUnits.splitNote(1.5, "lb", 4.0, "portion"))
    }

    @Test
    fun splitNoteFallsBackToUnitOnBlank() {
        assertEquals("Broke 1 unit into 6 unit", PantryUnits.splitNote(1.0, "  ", 6.0, ""))
    }
}
