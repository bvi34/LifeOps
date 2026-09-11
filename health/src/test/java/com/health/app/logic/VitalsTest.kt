package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VitalsTest {

    @Test
    fun `rejects the typos these bounds exist for`() {
        // An oxygen saturation with a zero too many, a pulse that lost its decimal point, and a
        // systolic typed into the diastolic box — the three ways these fields actually get filled in
        // wrongly, each of which used to save cleanly.
        assertNull(Vitals.OXYGEN.parse("920"))
        assertNull(Vitals.HEART_RATE.parse("720"))
        assertNull(Vitals.DIASTOLIC.parse("220"))
    }

    @Test
    fun `admits readings a real person produces, however unusual`() {
        // The bounds catch typing, not patients: a cyclist at rest, a feverish toddler, a newborn's
        // breathing and a sat of 88 on a bad night are all real, and all belong in the record.
        assertNotNull(Vitals.HEART_RATE.parse("38"))
        assertNotNull(Vitals.HEART_RATE.parse("190"))
        assertNotNull(Vitals.RESPIRATORY_RATE.parse("62"))
        assertNotNull(Vitals.OXYGEN.parse("88"))
        assertNotNull(Vitals.SYSTOLIC.parse("210"))
    }

    @Test
    fun `the ends of each range count as inside it`() {
        assertEquals(100.0, Vitals.OXYGEN.parse("100")!!, 0.0001)
        assertEquals(20.0, Vitals.HEART_RATE.parse("20")!!, 0.0001)
        assertEquals(300.0, Vitals.HEART_RATE.parse("300")!!, 0.0001)
    }

    @Test
    fun `blood pressure is bounded as two numbers, not one`() {
        // The commonest blood-pressure mistake is the two numbers the wrong way round, and a single
        // shared range could not notice: 210 over 130 is a reading, 130 over 210 is a slip.
        assertNotNull(Vitals.SYSTOLIC.parse("210"))
        assertNull(Vitals.DIASTOLIC.parse("210"))
    }

    @Test
    fun `parses what people actually type`() {
        assertEquals(36.8, Vitals.TEMPERATURE.parse(" 36,8 ")!!, 0.0001)
        assertEquals(72.0, Vitals.HEART_RATE.parse("72 ")!!, 0.0001)
        assertNull(Vitals.HEART_RATE.parse(""))
        assertNull(Vitals.HEART_RATE.parse("fast"))
    }

    @Test
    fun `every complaint says what was wrong, not that something was`() {
        // The sentence is half the rule: a rejection with nothing to act on teaches people to
        // distrust the field rather than to check the number.
        val ranges = listOf(
            Vitals.TEMPERATURE, Vitals.WEIGHT, Vitals.HEART_RATE,
            Vitals.SYSTOLIC, Vitals.DIASTOLIC, Vitals.OXYGEN, Vitals.RESPIRATORY_RATE
        )
        ranges.forEach { range ->
            assertEquals("the range is the right way round", true, range.min < range.max)
            assertEquals("it says something", true, range.complaint.length > 20)
        }
    }

    @Test
    fun `the two parsers that convert units are bounded from here too`() {
        // One place says what a believable reading is. If these drift apart, a temperature could be
        // refused by one rule and charted under another.
        assertNull(Temperature.parseToCelsius("986", TempUnit.FAHRENHEIT))
        assertNull(Weight.parseToKilograms("705", WeightUnit.KILOGRAMS))
        assertNotNull(Temperature.parseToCelsius("${Vitals.TEMPERATURE.max}", TempUnit.CELSIUS))
        assertNotNull(Weight.parseToKilograms("${Vitals.WEIGHT.min}", WeightUnit.KILOGRAMS))
    }
}
