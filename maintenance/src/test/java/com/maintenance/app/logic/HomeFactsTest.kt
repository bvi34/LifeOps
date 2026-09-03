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
 * The readings are tested separately because they fail separately: an address with no ZIP in it is a
 * different kind of gap from a type of home nobody picked.
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
        val facts = HomeLookup.read(address = "The cottage, up the lane")

        assertNull(facts.zip)
        assertNull(facts.climate)
        assertNotNull(facts.note)
        assertTrue(facts.note!!.contains("ZIP"))
    }

    @Test
    fun `no address at all says so rather than saying nothing`() {
        val facts = HomeLookup.read(yearBuilt = "1974")

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
        val facts = HomeLookup.read(address = "Unit 4, APO AE 09014")

        assertEquals("09014", facts.zip)
        assertNull(facts.climate)
        assertTrue(facts.note!!.contains("pick"))
    }

    // --- the pickers, which are the ordinary input ---

    @Test
    fun `the picker's keys are what a feature is normally read from`() {
        assertEquals(
            setOf(HomeFeature.SEPTIC, HomeFeature.SOLAR, HomeFeature.FUEL_GAS),
            HomeLookup.featuresIn("septic,solar,gas")
        )
        // Spacing is somebody else's business; this reads it either way.
        assertEquals(
            setOf(HomeFeature.POOL, HomeFeature.WELL),
            HomeLookup.featuresIn(" pool , well ")
        )
    }

    @Test
    fun `a key this build does not know is ignored rather than breaking the rest`() {
        assertEquals(
            setOf(HomeFeature.SEPTIC),
            HomeLookup.featuresIn("septic,something_a_later_build_added")
        )
    }

    @Test
    fun `the type of home is a picked key, and an unpicked one is simply absent`() {
        assertEquals(HomeStructure.MANUFACTURED, HomeLookup.read(structure = "manufactured").structure)
        assertEquals(HomeStructure.CONDO, HomeLookup.read(structure = " condo ").structure)
        assertNull(HomeLookup.read(structure = null).structure)
        assertNull(HomeLookup.read(structure = "houseboat").structure)
    }

    @Test
    fun `every option a picker offers is a key the reader knows`() {
        // The two enums and the two field specs are one thing said twice; this is the join.
        val structure = AssetKind.HOME.spec("structure")!!
        assertEquals(
            HomeStructure.entries.map { it.key },
            structure.options.map { it.key }
        )
        structure.options.forEach { assertNotNull(it.key, HomeStructure.of(it.key)) }

        val features = AssetKind.HOME.spec("features")!!
        assertEquals(
            HomeFeature.entries.map { it.key },
            features.options.map { it.key }
        )
        features.options.forEach { assertNotNull(it.key, HomeFeature.of(it.key)) }
    }

    // --- prose, which is the fallback rather than the input ---

    @Test
    fun `free text still reads, for a value the picker did not write`() {
        val found = HomeLookup.featuresIn(
            "Septic tank out back, private well, gas fireplace in the lounge; sump pump in the basement"
        )

        assertTrue(found.containsAll(setOf(HomeFeature.SEPTIC, HomeFeature.WELL, HomeFeature.FIREPLACE, HomeFeature.SUMP_PUMP)))
    }

    @Test
    fun `keys and prose in the same value both read`() {
        // The shape a half-migrated row has: a key the picker wrote, beside what somebody typed.
        assertEquals(
            setOf(HomeFeature.SOLAR, HomeFeature.SUMP_PUMP),
            HomeLookup.featuresIn("solar, sump pump in the basement")
        )
    }

    @Test
    fun `a phrase that denies a feature does not claim it`() {
        assertEquals(
            setOf(HomeFeature.SEPTIC),
            HomeLookup.featuresIn("Septic tank out back, no sprinklers")
        )
        assertTrue(HomeLookup.featuresIn("no pool, never had one").isEmpty())
    }

    @Test
    fun `a denial in one phrase does not cancel a claim in another`() {
        assertEquals(
            setOf(HomeFeature.SUMP_PUMP),
            HomeLookup.featuresIn("No pool.\nSump pump in the basement.")
        )
    }

    @Test
    fun `a word inside another word is not a feature`() {
        // The three that would otherwise be found everywhere: "stairwell", "spare room", "spool".
        assertTrue(HomeLookup.featuresIn("Stairwell needs a light").isEmpty())
        assertTrue(HomeLookup.featuresIn("Spare bedroom over the garage").isEmpty())
        assertTrue(HomeLookup.featuresIn("Cable spool left by the builders").isEmpty())
    }

    @Test
    fun `an empty field finds nothing and complains about nothing`() {
        assertTrue(HomeLookup.featuresIn(null).isEmpty())
        assertTrue(HomeLookup.featuresIn("   ").isEmpty())
    }

    // --- what the whole reading comes to ---

    @Test
    fun `everything read together is what a schedule gets chosen by`() {
        val facts = HomeLookup.read(
            address = "12 Elm Street\nMontpelier, VT 05602",
            structure = "manufactured",
            yearBuilt = "1948",
            features = "septic,fireplace",
            hasMortgage = true
        )

        assertEquals("05602", facts.zip)
        assertEquals(Climate.COLD, facts.climate)
        assertEquals(HomeStructure.MANUFACTURED, facts.structure)
        assertEquals(1948, facts.yearBuilt)
        assertEquals(setOf(HomeFeature.SEPTIC, HomeFeature.FIREPLACE), facts.features)
        assertTrue(facts.hasMortgage)
        assertNull("nothing was missing, so there is nothing to say", facts.note)
        assertFalse(facts.isEmpty)
        assertEquals("Manufactured or mobile home · Built 1948 · Cold winters", facts.descriptor)
        assertEquals("Septic system · Fireplace or wood stove", facts.detail)
    }

    @Test
    fun `a year that is not a year is simply absent`() {
        assertNull(HomeLookup.read(yearBuilt = "seventies").yearBuilt)
        assertNull(HomeLookup.read(yearBuilt = "12").yearBuilt)
        assertEquals(2019, HomeLookup.read(yearBuilt = " 2019 ").yearBuilt)
    }

    @Test
    fun `a house nobody has typed anything about is empty rather than wrong`() {
        val facts = HomeLookup.read()

        assertTrue(facts.isEmpty)
        assertEquals("", facts.descriptor)
        assertEquals("", facts.detail)
    }
}
