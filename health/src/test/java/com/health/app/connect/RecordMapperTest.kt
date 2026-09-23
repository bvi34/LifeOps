package com.health.app.connect

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import com.health.app.logic.ConnectKind
import com.health.app.logic.TempSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Health Connect's records reduced to Health's shape: the right kind, the headline number in the
 * kind's unit, and the rest kept in the detail rather than dropped.
 */
class RecordMapperTest {

    private val t0 = Instant.parse("2026-09-20T22:00:00Z")
    private val zone = ZoneOffset.ofHours(-5)

    private fun meta(id: String) = Metadata.manualEntryWithId(id)

    @Test
    fun `steps are a count over an interval`() {
        val mapped = RecordMapper.map(StepsRecord(t0, zone, t0.plusSeconds(3600), zone, 4200, meta("s1")))!!
        assertEquals("s1", mapped.id)
        assertEquals(ConnectKind.STEPS, mapped.kind)
        assertEquals(4200.0, mapped.value!!, 0.0)
        assertEquals(t0.toEpochMilli(), mapped.startAt)
        assertEquals(t0.plusSeconds(3600).toEpochMilli(), mapped.endAt)
        assertEquals(-5 * 3600, mapped.zoneOffsetSeconds)
        assertEquals("entered by hand", mapped.detail["recording"])
    }

    @Test
    fun `a weight is kilograms at a moment`() {
        val mapped = RecordMapper.map(WeightRecord(t0, zone, Mass.pounds(154.0), meta("w1")))!!
        assertEquals(ConnectKind.WEIGHT, mapped.kind)
        assertEquals(69.853, mapped.value!!, 0.001)
        assertNull(mapped.endAt)
    }

    @Test
    fun `blood pressure keeps both numbers`() {
        val mapped = RecordMapper.map(
            BloodPressureRecord(
                time = t0, zoneOffset = zone, metadata = meta("bp"),
                systolic = Pressure.millimetersOfMercury(121.0),
                diastolic = Pressure.millimetersOfMercury(79.0),
                bodyPosition = BloodPressureRecord.BODY_POSITION_SITTING_DOWN
            )
        )!!
        assertEquals(121.0, mapped.value!!, 0.0)
        assertEquals(79.0, mapped.secondaryValue!!, 0.0)
        assertEquals("sitting", mapped.detail["bodyPosition"])
    }

    @Test
    fun `a body temperature carries the site the fever rules read`() {
        val mapped = RecordMapper.map(
            BodyTemperatureRecord(
                time = t0, zoneOffset = zone, metadata = meta("bt"),
                temperature = Temperature.celsius(38.4),
                measurementLocation = BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_EAR
            )
        )!!
        assertEquals(38.4, mapped.value!!, 0.0)
        assertEquals(TempSite.EAR.key, mapped.detail["site"])
        assertEquals("ear", mapped.detail["location"])
    }

    @Test
    fun `sleep counts the stages asleep, not the time lying awake`() {
        val stages = listOf(
            SleepSessionRecord.Stage(t0, t0.plusSeconds(1800), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
            SleepSessionRecord.Stage(t0.plusSeconds(1800), t0.plusSeconds(5 * 3600), SleepSessionRecord.STAGE_TYPE_LIGHT),
            SleepSessionRecord.Stage(t0.plusSeconds(5 * 3600), t0.plusSeconds(7 * 3600), SleepSessionRecord.STAGE_TYPE_DEEP)
        )
        val mapped = RecordMapper.map(
            SleepSessionRecord(t0, zone, t0.plusSeconds(7 * 3600), zone, meta("sl"), stages = stages)
        )!!
        assertEquals(ConnectKind.SLEEP, mapped.kind)
        assertEquals(6.5, mapped.value!!, 0.001)
        @Suppress("UNCHECKED_CAST")
        val kept = mapped.detail["stages"] as List<Map<String, Any?>>
        assertEquals(listOf("awake in bed", "light", "deep"), kept.map { it["stage"] })
    }

    @Test
    fun `sleep with no stages is the whole session`() {
        val mapped = RecordMapper.map(SleepSessionRecord(t0, zone, t0.plusSeconds(8 * 3600), zone, meta("sl2")))!!
        assertEquals(8.0, mapped.value!!, 0.001)
    }

    @Test
    fun `heart rate is the average, with the samples kept`() {
        val samples = listOf(
            HeartRateRecord.Sample(t0, 60),
            HeartRateRecord.Sample(t0.plusSeconds(60), 70),
            HeartRateRecord.Sample(t0.plusSeconds(120), 80)
        )
        val mapped = RecordMapper.map(HeartRateRecord(t0, zone, t0.plusSeconds(180), zone, samples, meta("hr")))!!
        assertEquals(70.0, mapped.value!!, 0.0)
        assertEquals(60.0, mapped.detail["min"])
        assertEquals(80.0, mapped.detail["max"])
        assertEquals(3, (mapped.detail["samples"] as List<*>).size)
    }

    @Test
    fun `a meal keeps every nutrient it named, and only those`() {
        val mapped = RecordMapper.map(
            NutritionRecord(
                startTime = t0, startZoneOffset = zone, endTime = t0.plusSeconds(900), endZoneOffset = zone,
                metadata = meta("n1"), name = "Porridge", energy = Energy.kilocalories(310.0),
                protein = Mass.grams(11.0), sugar = Mass.grams(9.5)
            )
        )!!
        assertEquals(310.0, mapped.value!!, 0.001)
        assertEquals("Porridge", mapped.detail["name"])
        assertEquals(mapOf("protein" to 11.0, "sugar" to 9.5), mapped.detail["grams"])
    }
}
