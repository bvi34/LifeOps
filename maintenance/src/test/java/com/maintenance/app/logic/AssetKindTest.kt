package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetKindTest {

    @Test
    fun `every kind is coherent - unique keys, labelled fields, no repeats`() {
        assertEquals(AssetKind.entries.size, AssetKind.entries.map { it.key }.toSet().size)
        AssetKind.entries.forEach { kind ->
            assertTrue(kind.label.isNotBlank())
            assertTrue(kind.plural.isNotBlank())
            assertEquals(
                "${kind.label} repeats an attribute key",
                kind.attributes.size,
                kind.attributes.map { it.key }.toSet().size
            )
            kind.attributes.forEach { assertTrue(it.label.isNotBlank()) }
        }
    }

    @Test
    fun `a vehicle is identified by its VIN and counts miles`() {
        assertEquals(MeterUnit.MILES, AssetKind.VEHICLE.meter)
        val vin = AssetKind.VEHICLE.spec("vin")
        assertNotNull(vin)
        assertEquals(AttributeCheck.VIN, vin!!.check)
    }

    @Test
    fun `a vehicle asks for everything on the paperwork, and asks for none of it twice`() {
        val keys = AssetKind.VEHICLE.attributes.map { it.key }
        assertEquals(
            listOf(
                "vin", "trim", "bodyStyle", "engine", "fuel", "transmission",
                "driveType", "color", "licensePlate", "plateState", "tireSize", "oilSpec"
            ),
            keys
        )
        keys.forEach { key -> assertNotNull("$key has no spec", AssetKind.VEHICLE.spec(key)) }
    }

    @Test
    fun `everything a VIN decode returns has a vehicle field to land in`() {
        val facts = VehicleFacts(
            make = "Jeep",
            model = "Wrangler",
            year = 2018,
            trim = "Unlimited Sport",
            bodyClass = "Sport Utility Vehicle (SUV)",
            driveType = "4WD/4-Wheel Drive",
            engineCylinders = 6,
            displacementLitres = 3.6,
            fuel = "Gasoline",
            transmission = "Automatic"
        )

        // Make, model and year are columns on the asset; the rest are the kind's own fields, and
        // every one of them has somewhere to go.
        listOf("trim", "bodyStyle", "engine", "fuel", "transmission", "driveType").forEach { key ->
            assertNotNull("a decoded $key has nowhere to land", AssetKind.VEHICLE.spec(key))
        }
        assertEquals("3.6L V6", facts.engine)
        assertEquals("4WD", facts.drive)
    }

    @Test
    fun `only the kinds that wear a meter have one`() {
        assertNull(AssetKind.HOME.meter)
        assertNull(AssetKind.APPLIANCE.meter)
        assertEquals(MeterUnit.HOURS, AssetKind.EQUIPMENT.meter)
    }

    @Test
    fun `an unknown kind opens as Other rather than throwing`() {
        assertEquals(AssetKind.OTHER, AssetKind.of("boat"))
        assertEquals(AssetKind.OTHER, AssetKind.of(null))
        assertEquals(AssetKind.HOME, AssetKind.of("home"))
    }

    @Test
    fun `blank is always allowed - half the stickers are unread on the day you type it in`() {
        AssetKind.entries.flatMap { it.attributes }.forEach { spec ->
            assertNull("${spec.key} refused a blank", AssetAttributes.problem(spec, ""))
            assertNull("${spec.key} refused a blank", AssetAttributes.problem(spec, "   "))
        }
    }

    @Test
    fun `a VIN is tidied on the way in and complained about only when wrong`() {
        val vin = AssetKind.VEHICLE.spec("vin")!!

        assertEquals("1HGCM82633A004352", AssetAttributes.normalise(vin, " 1hgcm826-33a004352 "))
        assertNull(AssetAttributes.problem(vin, "1HGCM82633A004352"))
        assertEquals(Vin.Problem.LENGTH.message, AssetAttributes.problem(vin, "1HGCM8"))
        assertEquals(Vin.Problem.CHECK_DIGIT.message, AssetAttributes.problem(vin, "5YJ3E1EA7JF006588"))
    }

    @Test
    fun `numbers are checked as numbers and years as years`() {
        val yearBuilt = AssetKind.HOME.spec("yearBuilt")!!
        val squareFeet = AssetKind.HOME.spec("squareFeet")!!
        val lotSize = AssetKind.HOME.spec("lotSize")!!

        assertNull(AssetAttributes.problem(yearBuilt, "1974"))
        assertNotNull(AssetAttributes.problem(yearBuilt, "seventies"))
        assertNotNull(AssetAttributes.problem(yearBuilt, "12"))

        assertNull(AssetAttributes.problem(squareFeet, "1,850"))
        assertNotNull(AssetAttributes.problem(squareFeet, "1850.5"))

        assertNull(AssetAttributes.problem(lotSize, "0.34"))
        assertNotNull(AssetAttributes.problem(lotSize, "a third"))
    }

    @Test
    fun `a picker is chosen from, not typed into`() {
        val features = AssetKind.HOME.spec("features")!!
        val structure = AssetKind.HOME.spec("structure")!!

        assertEquals(AttributeInput.CHOICES, features.input)
        assertTrue(features.isPicker)
        assertTrue(features.options.isNotEmpty())
        features.options.forEach { assertTrue(it.label.isNotBlank()) }
        assertEquals(features.options.size, features.options.map { it.key }.toSet().size)

        // Ticking, unticking, and the fact that a single choice replaces rather than accumulates.
        assertEquals("septic", AssetAttributes.toggle(features, "", "septic"))
        assertEquals("septic,solar", AssetAttributes.toggle(features, "septic", "solar"))
        assertEquals("solar", AssetAttributes.toggle(features, "septic,solar", "septic"))
        assertEquals("condo", AssetAttributes.toggle(structure, "conventional", "condo"))
        assertEquals("", AssetAttributes.toggle(structure, "condo", "condo"))
    }

    @Test
    fun `a picker reads as labels and stores as keys`() {
        val features = AssetKind.HOME.spec("features")!!

        assertEquals("Septic system · Solar panels", AssetAttributes.display(features, "solar,septic"))
        assertEquals("", AssetAttributes.display(features, ""))
        // Order is the spec's, not the order things were ticked, so one answer reads one way.
        assertEquals(
            AssetAttributes.display(features, "septic,solar"),
            AssetAttributes.display(features, "solar,septic")
        )
    }

    @Test
    fun `a picker never complains, and keeps a key it does not know`() {
        val features = AssetKind.HOME.spec("features")!!

        assertNull(AssetAttributes.problem(features, "septic,invented_by_a_later_build"))
        assertEquals(
            listOf("septic", "invented_by_a_later_build"),
            AssetAttributes.chosen(features, "septic, invented_by_a_later_build")
        )
        // And it survives being normalised on the way back in, rather than being dropped.
        assertEquals(
            "septic,invented_by_a_later_build",
            AssetAttributes.normalise(features, " septic , invented_by_a_later_build ")
        )
    }

    @Test
    fun `meter readings are written the way they are read`() {
        assertEquals("12,400 mi", MeterUnit.MILES.format(12_400))
        assertEquals("340 hr", MeterUnit.HOURS.format(340))
        assertEquals("1,234,567", MeterUnit.group(1_234_567))
    }
}
