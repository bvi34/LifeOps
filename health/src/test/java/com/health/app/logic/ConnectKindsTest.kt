package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a day of Health Connect data comes to, and how its numbers read.
 *
 * The case that matters most is two devices counting the same steps: summing both would credit a
 * walk taken once as two, which is the kind of number nobody questions and everybody repeats.
 */
class ConnectKindsTest {

    private val hour = 60 * 60 * 1000L
    private val dayStart = 1_700_000_000_000L
    private val dayEnd = dayStart + 24 * hour

    private fun point(kind: ConnectKind, at: Long, value: Double?, source: String? = "phone", end: Long? = null, secondary: Double? = null) =
        ConnectPoint(kind, at, end, value, secondary, source)

    @Test
    fun `every kind has a key of its own`() {
        assertEquals(ConnectKind.entries.size, ConnectKind.entries.map { it.key }.toSet().size)
        ConnectKind.entries.forEach { assertEquals(it, ConnectKind.fromKey(it.key)) }
        assertNull(ConnectKind.fromKey("not_a_kind"))
    }

    @Test
    fun `summed kinds add up within one source`() {
        val figures = ConnectSummaries.day(
            listOf(
                point(ConnectKind.STEPS, dayStart + hour, 1000.0, end = dayStart + 2 * hour),
                point(ConnectKind.STEPS, dayStart + 3 * hour, 2500.0, end = dayStart + 4 * hour)
            ),
            dayStart, dayEnd
        )
        assertEquals(3500.0, figures.single().value!!, 0.001)
        assertEquals(2, figures.single().count)
    }

    @Test
    fun `two sources counting the same walk are not added together`() {
        val figures = ConnectSummaries.day(
            listOf(
                point(ConnectKind.STEPS, dayStart + hour, 4000.0, source = "phone", end = dayStart + 2 * hour),
                point(ConnectKind.STEPS, dayStart + hour, 4200.0, source = "watch", end = dayStart + 2 * hour),
                point(ConnectKind.STEPS, dayStart + 5 * hour, 300.0, source = "watch", end = dayStart + 6 * hour)
            ),
            dayStart, dayEnd
        )
        assertEquals("the watch's 4,500, not 8,500", 4500.0, figures.single().value!!, 0.001)
    }

    @Test
    fun `a state kind is its latest reading, not a total`() {
        val figures = ConnectSummaries.day(
            listOf(
                point(ConnectKind.WEIGHT, dayStart + hour, 71.0),
                point(ConnectKind.WEIGHT, dayStart + 9 * hour, 70.4)
            ),
            dayStart, dayEnd
        )
        assertEquals(70.4, figures.single().value!!, 0.001)
    }

    @Test
    fun `last night's sleep belongs to the morning it ended in`() {
        val sleep = point(ConnectKind.SLEEP, dayStart - 2 * hour, 7.5, end = dayStart + 6 * hour)
        assertEquals(1, ConnectSummaries.day(listOf(sleep), dayStart, dayEnd).size)
        assertEquals(0, ConnectSummaries.day(listOf(sleep), dayStart - 24 * hour, dayStart).size)
    }

    @Test
    fun `records outside the day are left out`() {
        val figures = ConnectSummaries.day(
            listOf(point(ConnectKind.STEPS, dayEnd + hour, 100.0, end = dayEnd + 2 * hour)),
            dayStart, dayEnd
        )
        assertEquals(emptyList<DayFigure>(), figures)
    }

    @Test
    fun `latest picks the newest of each kind`() {
        val figures = ConnectSummaries.latest(
            listOf(
                point(ConnectKind.RESTING_HEART_RATE, dayStart, 58.0),
                point(ConnectKind.RESTING_HEART_RATE, dayStart + hour, 61.0),
                point(ConnectKind.HEIGHT, dayStart, 1.72)
            )
        )
        assertEquals(listOf(ConnectKind.HEIGHT, ConnectKind.RESTING_HEART_RATE).sortedBy { it.ordinal }, figures.map { it.kind })
        assertEquals(61.0, figures.first { it.kind == ConnectKind.RESTING_HEART_RATE }.value!!, 0.001)
    }

    @Test
    fun `numbers read in the units a person would say them in`() {
        assertEquals("8,412 steps", ConnectFormat.value(ConnectKind.STEPS, 8412.0, null))
        assertEquals("5.2 km", ConnectFormat.value(ConnectKind.DISTANCE, 5210.0, null))
        assertEquals("850 m", ConnectFormat.value(ConnectKind.DISTANCE, 850.0, null))
        assertEquals("7 h 30 min", ConnectFormat.value(ConnectKind.SLEEP, 7.5, null))
        assertEquals("45 min", ConnectFormat.value(ConnectKind.EXERCISE, 45.0, null))
        assertEquals("120/80 mmHg", ConnectFormat.value(ConnectKind.BLOOD_PRESSURE, 120.0, 80.0))
        assertEquals("97%", ConnectFormat.value(ConnectKind.OXYGEN_SATURATION, 97.0, null))
        assertEquals("172 cm", ConnectFormat.value(ConnectKind.HEIGHT, 1.72, null))
        assertEquals("Recorded", ConnectFormat.value(ConnectKind.MENSTRUATION_FLOW, null, null))
    }

    @Test
    fun `weights and temperatures follow the household's units`() {
        assertEquals(Weight.format(70.0, WeightUnit.POUNDS), ConnectFormat.value(ConnectKind.WEIGHT, 70.0, null, WeightUnit.POUNDS))
        assertEquals(
            Temperature.format(37.0, TempUnit.FAHRENHEIT),
            ConnectFormat.value(ConnectKind.BODY_TEMPERATURE, 37.0, null, tempUnit = TempUnit.FAHRENHEIT)
        )
        assertEquals("+0.90 °F", ConnectFormat.value(ConnectKind.SKIN_TEMPERATURE, 0.5, null, tempUnit = TempUnit.FAHRENHEIT))
    }

    @Test
    fun `durations round to the minute`() {
        assertEquals("0 min", ConnectFormat.duration(0.0))
        assertEquals("2 h", ConnectFormat.duration(2 * 3_600_000.0))
        assertEquals("1 h 1 min", ConnectFormat.duration(3_660_000.0))
    }
}
