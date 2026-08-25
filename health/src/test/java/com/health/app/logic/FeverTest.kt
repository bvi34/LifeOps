package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeverTest {

    @Test
    fun `bands follow the oral-equivalent scale`() {
        assertEquals(FeverBand.NORMAL, Fever.bandFor(36.8))
        assertEquals(FeverBand.ELEVATED, Fever.bandFor(37.6))
        assertEquals(FeverBand.FEVER, Fever.bandFor(38.0))
        assertEquals(FeverBand.HIGH_FEVER, Fever.bandFor(39.2))
        assertEquals(FeverBand.VERY_HIGH, Fever.bandFor(40.1))
        assertEquals(FeverBand.LOW, Fever.bandFor(34.4))
    }

    @Test
    fun `the site changes the verdict on the same number`() {
        // 37.7 under the arm is a fever once adjusted; the same 37.7 in the mouth is not.
        assertTrue(Fever.isFever(37.7, TempSite.AXILLARY))
        assertFalse(Fever.isFever(37.7, TempSite.ORAL))

        val armpit = Fever.assess(37.7, TempSite.AXILLARY)
        assertEquals(38.2, armpit.oralEquivalentC, 0.001)
        assertTrue(armpit.reasons.any { it.contains("Armpit") })
    }

    @Test
    fun `a rectal reading is adjusted down, not up`() {
        val rectal = Fever.assess(38.2, TempSite.RECTAL)
        assertEquals(37.7, rectal.oralEquivalentC, 0.001)
        assertEquals(FeverBand.ELEVATED, rectal.band)
    }

    @Test
    fun `any fever in a newborn is an immediate-care flag`() {
        val newborn = Fever.assess(38.1, TempSite.RECTAL, ageMonths = 2)
        assertEquals(CareLevel.SEEK_CARE_NOW, newborn.careLevel)
        assertTrue(newborn.reasons.any { it.contains("Under 3 months") })

        // The same reading in an adult is a watch-it, not an emergency.
        val adult = Fever.assess(38.6, TempSite.ORAL, ageMonths = 12 * 34)
        assertEquals(CareLevel.MONITOR, adult.careLevel)
    }

    @Test
    fun `the newborn flag is judged on the reading as taken, not only on the adjustment`() {
        // A rectal 38.0 is *the* published newborn threshold. Adjusting it to a 37.5 oral-equivalent
        // and stopping there would call the textbook case "slightly raised".
        assertEquals(CareLevel.SEEK_CARE_NOW, Fever.assess(38.0, TempSite.RECTAL, ageMonths = 2).careLevel)
        // The band itself still reports the adjusted scale honestly.
        assertEquals(FeverBand.ELEVATED, Fever.assess(38.0, TempSite.RECTAL, ageMonths = 2).band)
        // And an armpit reading, which runs low, escalates on the adjusted value.
        assertEquals(CareLevel.SEEK_CARE_NOW, Fever.assess(37.6, TempSite.AXILLARY, ageMonths = 2).careLevel)
    }

    @Test
    fun `a young infant with a high fever escalates past calling`() {
        val infant = Fever.assess(39.4, TempSite.ORAL, ageMonths = 4)
        assertEquals(CareLevel.SEEK_CARE_NOW, infant.careLevel)

        val mild = Fever.assess(38.2, TempSite.ORAL, ageMonths = 4)
        assertEquals(CareLevel.CALL_DOCTOR, mild.careLevel)
    }

    @Test
    fun `forty degrees is urgent at every age, and hypothermia is never routine`() {
        assertEquals(CareLevel.SEEK_CARE_NOW, Fever.assess(40.2, TempSite.ORAL, ageMonths = 480).careLevel)
        assertEquals(CareLevel.SEEK_CARE_NOW, Fever.assess(40.2, TempSite.ORAL, ageMonths = null).careLevel)
        assertEquals(CareLevel.CALL_DOCTOR, Fever.assess(34.6, TempSite.ORAL).careLevel)
    }

    @Test
    fun `an unknown age falls back to the adult rules rather than the strictest ones`() {
        val unknown = Fever.assess(38.4, TempSite.ORAL, ageMonths = null)
        assertEquals(CareLevel.MONITOR, unknown.careLevel)
        assertTrue(unknown.isFever)
    }
}
