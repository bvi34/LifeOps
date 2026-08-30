package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures here are trimmed from **real** vPIC responses (`DecodeVinValues`), noise fields and
 * all — a hand-written ideal payload would have proved only that the parser can read itself.
 */
class VpicParserTest {

    private val wrangler = """
        {
         "Count": 1,
         "Message": "Results returned successfully",
         "Results": [
          {
           "Make": "JEEP",
           "Model": "Wrangler",
           "ModelYear": "2018",
           "Trim": "Unlimited Sport",
           "BodyClass": "Sport Utility Vehicle [SUV]/Multipurpose Vehicle [MPV]",
           "DriveType": "4WD/4-Wheel Drive/4x4",
           "EngineCylinders": "6",
           "DisplacementL": "3.6",
           "FuelTypePrimary": "Gasoline",
           "TransmissionStyle": "Manual/Standard",
           "Manufacturer": "FCA US LLC",
           "PlantCity": "TOLEDO",
           "ErrorCode": "1",
           "ErrorText": "1 - Check Digit (9th position) does not calculate properly",
           "Series": "",
           "ABS": "Standard",
           "AirBagLocFront": "1st Row (Driver and Passenger)"
          }
         ]
        }
    """.trimIndent()

    @Test
    fun `a decode becomes the dozen facts the app actually uses`() {
        val facts = VpicParser.parse(wrangler)!!

        assertEquals("Jeep", facts.make)
        assertEquals("Wrangler", facts.model)
        assertEquals(2018, facts.year)
        assertEquals("Unlimited Sport", facts.trim)
        assertEquals(3.6, facts.displacementLitres!!, 0.001)
        assertEquals(6, facts.engineCylinders)
        assertEquals("Gasoline", facts.fuel)
        assertEquals("Toledo", facts.plant)
        assertEquals("2018 Jeep Wrangler", facts.descriptor)
        assertEquals("3.6L V6 · 4WD · Unlimited Sport", facts.detail)
    }

    @Test
    fun `an empty field is an absent field, not an empty one`() {
        // vPIC fills all 154 fields whether it knows them or not; "" is its way of shrugging, and a
        // shrug that arrives as an empty string is a shrug every screen downstream has to remember.
        val vague = wrangler
            .replace("\"Trim\": \"Unlimited Sport\"", "\"Trim\": \"\"")
            .replace("\"DisplacementL\": \"3.6\"", "\"DisplacementL\": \"\"")

        val facts = VpicParser.parse(vague)!!

        assertNull(facts.trim)
        assertNull(facts.displacementLitres)
        assertEquals("2018 Jeep Wrangler", facts.descriptor)
        // …and the line under it simply carries less, rather than carrying separators around nothing.
        assertEquals("4WD", facts.detail)
    }

    @Test
    fun `a check digit vPIC dislikes is carried as a note, not thrown`() {
        val facts = VpicParser.parse(wrangler)!!

        assertEquals("Check Digit (9th position) does not calculate properly", facts.note)
        // …and the decode is still perfectly usable, which is the point.
        assertEquals("Wrangler", facts.model)
    }

    @Test
    fun `a clean decode says nothing`() {
        val clean = wrangler
            .replace("\"ErrorCode\": \"1\"", "\"ErrorCode\": \"0\"")
            .replace(
                "\"ErrorText\": \"1 - Check Digit (9th position) does not calculate properly\"",
                "\"ErrorText\": \"0 - VIN decoded clean. Check Digit (9th position) is correct\""
            )

        assertNull(VpicParser.parse(clean)!!.note)
    }

    @Test
    fun `nothing useful comes back as nothing rather than as an empty vehicle`() {
        val empty = """{"Count":1,"Results":[{"Make":"","Model":"","ModelYear":"","ErrorCode":"11"}]}"""

        assertNull(VpicParser.parse(empty))
    }

    @Test
    fun `a payload that isn't vPIC at all is refused quietly`() {
        assertNull(VpicParser.parse("not json"))
        assertNull(VpicParser.parse("""{"unexpected":true}"""))
        assertNull(VpicParser.parse("""{"Results":[]}"""))
    }

    @Test
    fun `the serial never leaves the device`() {
        // The privacy rule of the whole feature, and the reason it is a function rather than a habit.
        assertEquals("1C4HJXDG5JW******", Vin.decodeQuery("1C4HJXDG5JW123456"))
        assertEquals("1C4HJXDG5JW******", Vin.decodeQuery("1c4hjxdg5jw123456"))
        assertTrue("the serial must not survive", Vin.decodeQuery("1C4HJXDG5JW123456")!!.endsWith("******"))
        assertEquals(Vin.LENGTH, Vin.decodeQuery("1C4HJXDG5JW123456")!!.length)
        // Two different trucks off the same line ask an identical question.
        assertEquals(Vin.decodeQuery("1C4HJXDG5JW123456"), Vin.decodeQuery("1C4HJXDG5JW999999"))
        // Too little to be worth asking about.
        assertNull(Vin.decodeQuery("1C4HJ"))
        assertNull(Vin.decodeQuery(""))
    }
}
