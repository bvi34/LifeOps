package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a house off its own fields.
 *
 * The three readings are tested separately because they fail separately: an address with no ZIP in
 * it is a different kind of gap from a sentence that mentions a stairwell.
 */
class HomeFactsTest {

    // --- the ZIP, out of an address somebody typed on three lines ---

    @Test
    fun `the ZIP is the last five digits, not the house number`() {
        assertEquals("05401", HomeLookup.zipIn("128 Main Street\nBurlington, VT 05401"))
        assertEquals("80301", HomeLookup.zipIn("4900 Pearl East Circle, Boulder CO 80301"))
    }

    @Test
    fun `a ZIP plus four is a ZIP, and the four are dropped`() {
        assertEquals("30301", HomeLookup.zipIn("Atlanta, GA 30301-1234"))
    }

    @Test
    fun `no ZIP is not an error - it is a sentence saying what could not be worked out`() {
        val facts = HomeLookup.read(address = "The cottage, up the lane", yearBuilt = null, systems = null)

        assertNull(facts.zip)
        assertNull(facts.climate)
        assertNotNull(facts.note)
        assertTrue(facts.note!!.contains("ZIP"))
    }

    @Test
    fun `no address at all says so rather than saying nothing`() {
        val facts = HomeLookup.read(address = null, yearBuilt = "1974", systems = null)

        assertNull(facts.climate)
        assertEquals(1974, facts.yearBuilt)
        assertNotNull(facts.note)
    }

    // --- the climate the ZIP implies ---

    @Test
    fun `a ZIP lands in the climate its part of the country has`() {
        assertEquals(Climate.COLD, HomeLookup.climateOf("05401"))       // Burlington, Vermont
        assertEquals(Climate.TEMPERATE, HomeLookup.climateOf("07001"))  // New Jersey
        assertEquals(Climate.HOT_HUMID, HomeLookup.climateOf("33101"))  // Miami
        assertEquals(Climate.HOT_DRY, HomeLookup.climateOf("85001"))    // Phoenix
        assertEquals(Climate.MARINE, HomeLookup.climateOf("98101"))     // Seattle
        assertEquals(Climate.COLD, HomeLookup.climateOf("99501"))       // Anchorage
    }

    @Test
    fun `a ZIP the table does not cover is null rather than a guess`() {
        // 09xxx is military mail with no geography behind it.
        assertNull(HomeLookup.climateOf("09014"))
        assertNull(HomeLookup.climateOf(null))
        assertNull(HomeLookup.climateOf("not a zip"))
    }

    @Test
    fun `an unknown ZIP says the schedules are there to pick from`() {
        val facts = HomeLookup.read(address = "Unit 4, APO AE 09014", yearBuilt = null, systems = null)

        assertEquals("09014", facts.zip)
        assertNull(facts.climate)
        assertTrue(facts.note!!.contains("pick"))
    }

    // --- the systems, out of a sentence ---

    @Test
    fun `the systems are read out of the words people actually write`() {
        val found = HomeLookup.systemsIn(
            "Septic tank out back, private well, gas fireplace in the lounge; sump pump in the basement"
        )

        assertEquals(
            setOf(HomeSystem.SEPTIC, HomeSystem.WELL, HomeSystem.FIREPLACE, HomeSystem.SUMP_PUMP),
            found
        )
    }

    @Test
    fun `a phrase that denies a system does not claim it`() {
        assertEquals(
            setOf(HomeSystem.SEPTIC),
            HomeLookup.systemsIn("Septic tank, no sprinklers")
        )
        assertTrue(HomeLookup.systemsIn("no pool, never had one").isEmpty())
    }

    @Test
    fun `a denial in one phrase does not cancel a claim in another`() {
        assertEquals(
            setOf(HomeSystem.SUMP_PUMP),
            HomeLookup.systemsIn("No pool.\nSump pump in the basement.")
        )
    }

    @Test
    fun `a word inside another word is not a system`() {
        // The three that would otherwise be found everywhere: "stairwell", "spare room", "spool".
        assertTrue(HomeLookup.systemsIn("Stairwell needs a light").isEmpty())
        assertTrue(HomeLookup.systemsIn("Spare bedroom over the garage").isEmpty())
        assertTrue(HomeLookup.systemsIn("Cable spool left by the builders").isEmpty())
    }

    @Test
    fun `the words a chimney is written in all find the same system`() {
        listOf("wood stove", "log burner", "chimney", "open fireplace").forEach { written ->
            assertEquals(written, setOf(HomeSystem.FIREPLACE), HomeLookup.systemsIn(written))
        }
    }

    @Test
    fun `an empty field finds nothing and complains about nothing`() {
        assertTrue(HomeLookup.systemsIn(null).isEmpty())
        assertTrue(HomeLookup.systemsIn("   ").isEmpty())
    }

    // --- what the whole reading comes to ---

    @Test
    fun `everything read together is what a schedule gets chosen by`() {
        val facts = HomeLookup.read(
            address = "12 Elm Street\nMontpelier, VT 05602",
            yearBuilt = "1948",
            systems = "Septic tank, wood stove",
            hasMortgage = true
        )

        assertEquals("05602", facts.zip)
        assertEquals(Climate.COLD, facts.climate)
        assertEquals(1948, facts.yearBuilt)
        assertEquals(setOf(HomeSystem.SEPTIC, HomeSystem.FIREPLACE), facts.systems)
        assertTrue(facts.hasMortgage)
        assertNull("nothing was missing, so there is nothing to say", facts.note)
        assertFalse(facts.isEmpty)
        assertEquals("Built 1948 · Cold winters", facts.descriptor)
        assertEquals("Septic tank · Fireplace or stove", facts.detail)
    }

    @Test
    fun `a year that is not a year is simply absent`() {
        assertNull(HomeLookup.read(null, "seventies", null).yearBuilt)
        assertNull(HomeLookup.read(null, "12", null).yearBuilt)
        assertEquals(2019, HomeLookup.read(null, " 2019 ", null).yearBuilt)
    }

    @Test
    fun `a house nobody has typed anything about is empty rather than wrong`() {
        val facts = HomeLookup.read(null, null, null)

        assertTrue(facts.isEmpty)
        assertEquals("", facts.descriptor)
        assertEquals("", facts.detail)
    }
}
