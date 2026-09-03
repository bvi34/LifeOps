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
        assertNull(facts.region)
        assertNull(facts.climate)
        assertNotNull(facts.note)
        assertTrue(facts.note!!.contains("ZIP"))
    }

    @Test
    fun `no address at all says so rather than saying nothing`() {
        val facts = HomeLookup.read(yearBuilt = "1974")

        assertNull(facts.region)
        assertNull(facts.climate)
        assertEquals(1974, facts.yearBuilt)
        assertNotNull(facts.note)
    }

    // --- the region the ZIP implies, and the climate and hazards it carries ---

    @Test
    fun `a ZIP lands in the region its part of the country is in`() {
        assertEquals(Region.NEW_ENGLAND, HomeLookup.regionOf("05401"))        // Burlington, Vermont
        assertEquals(Region.MID_ATLANTIC, HomeLookup.regionOf("07001"))       // New Jersey
        assertEquals(Region.SOUTHEAST, HomeLookup.regionOf("33101"))          // Miami
        assertEquals(Region.DRY_SOUTHWEST, HomeLookup.regionOf("85001"))      // Phoenix
        assertEquals(Region.PACIFIC_NORTHWEST, HomeLookup.regionOf("98101"))  // Seattle
        assertEquals(Region.MOUNTAIN_WEST, HomeLookup.regionOf("99206"))      // Spokane
        assertEquals(Region.ALASKA, HomeLookup.regionOf("99501"))             // Anchorage
        assertEquals(Region.GULF_COAST, HomeLookup.regionOf("70112"))         // New Orleans
        assertEquals(Region.CALIFORNIA, HomeLookup.regionOf("94110"))         // San Francisco
    }

    @Test
    fun `the climate a ZIP falls in is the region's, and never stored beside it`() {
        assertEquals(Climate.COLD, HomeLookup.climateOf("05401"))
        assertEquals(Climate.TEMPERATE, HomeLookup.climateOf("07001"))
        assertEquals(Climate.HOT_HUMID, HomeLookup.climateOf("33101"))
        assertEquals(Climate.HOT_DRY, HomeLookup.climateOf("85001"))
        assertEquals(Climate.MARINE, HomeLookup.climateOf("98101"))
        assertEquals(Climate.COLD, HomeLookup.climateOf("99501"))
    }

    @Test
    fun `a region carries what the weather does at its worst, not only day to day`() {
        assertEquals(setOf(Hazard.HURRICANE), HomeLookup.read(address = "Miami FL 33101").hazards)
        assertEquals(setOf(Hazard.WILDFIRE), HomeLookup.read(address = "Phoenix AZ 85001").hazards)
        assertEquals(
            setOf(Hazard.EARTHQUAKE, Hazard.WILDFIRE),
            HomeLookup.read(address = "Seattle WA 98101").hazards
        )
        assertEquals(
            setOf(Hazard.HURRICANE, Hazard.SEVERE_STORM),
            HomeLookup.read(address = "New Orleans LA 70112").hazards
        )
        // Most of the country has none of them, and that is a real answer.
        assertTrue(HomeLookup.read(address = "Burlington VT 05401").hazards.isEmpty())
    }

    @Test
    fun `a ZIP the table does not cover is null rather than a guess`() {
        // 09xxx is military mail with no geography behind it.
        assertNull(HomeLookup.regionOf("09014"))
        assertNull(HomeLookup.climateOf("09014"))
        assertNull(HomeLookup.regionOf(null))
        assertNull(HomeLookup.regionOf("not a zip"))
    }

    @Test
    fun `an unknown ZIP says a region is there to pick`() {
        val facts = HomeLookup.read(address = "Unit 4, APO AE 09014")

        assertEquals("09014", facts.zip)
        assertNull(facts.region)
        assertNull(facts.climate)
        assertEquals(RegionSource.NONE, facts.regionSource)
        assertTrue(facts.note!!.contains("Pick a region"))
    }

    @Test
    fun `a region worked out from a ZIP says it was worked out`() {
        val facts = HomeLookup.read(address = "128 Main Street\nBurlington, VT 05401")

        assertEquals(Region.NEW_ENGLAND, facts.region)
        assertEquals(RegionSource.ZIP, facts.regionSource)
        assertNull("a guess that worked is not something to apologise for", facts.note)
    }

    @Test
    fun `a picked region wins over the ZIP, and stops being a guess`() {
        // The case the field exists for: a ZIP prefix says California and this house is in the
        // mountains behind it. Nothing re-derives what somebody picked.
        val facts = HomeLookup.read(
            address = "Truckee, CA 96161",
            region = "mountain_west"
        )

        assertEquals(Region.MOUNTAIN_WEST, facts.region)
        assertEquals(RegionSource.PICKED, facts.regionSource)
        assertEquals(Climate.COLD, facts.climate)
        assertEquals("96161", facts.zip)
    }

    @Test
    fun `a picked region carries a house with no address at all`() {
        val facts = HomeLookup.read(region = "gulf_coast")

        assertEquals(Region.GULF_COAST, facts.region)
        assertEquals(RegionSource.PICKED, facts.regionSource)
        assertNull("nothing was missing, so there is nothing to say", facts.note)
    }

    @Test
    fun `a region key this build does not know falls back to the ZIP`() {
        val facts = HomeLookup.read(address = "Miami FL 33101", region = "atlantis")

        assertEquals(Region.SOUTHEAST, facts.region)
        assertEquals(RegionSource.ZIP, facts.regionSource)
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
        val region = AssetKind.HOME.spec("region")!!
        assertEquals(
            Region.entries.map { it.key },
            region.options.map { it.key }
        )
        region.options.forEach { assertNotNull(it.key, Region.of(it.key)) }

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
        assertEquals(Region.NEW_ENGLAND, facts.region)
        assertEquals(Climate.COLD, facts.climate)
        assertEquals(HomeStructure.MANUFACTURED, facts.structure)
        assertEquals(1948, facts.yearBuilt)
        assertEquals(setOf(HomeFeature.SEPTIC, HomeFeature.FIREPLACE), facts.features)
        assertTrue(facts.hasMortgage)
        assertNull("nothing was missing, so there is nothing to say", facts.note)
        assertFalse(facts.isEmpty)
        assertEquals("Manufactured or mobile home · Built 1948 · New England", facts.descriptor)
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
