package com.lifeops.app.util

import com.lifeops.app.data.model.Person
import com.lifeops.app.data.model.SunSensitivity
import org.junit.Assert.*
import org.junit.Test

class PersonMapperTest {

    @Test
    fun `sun sensitivity parses known values and falls back to moderate`() {
        assertEquals(SunSensitivity.LOW, SunSensitivity.from("low"))
        assertEquals(SunSensitivity.HIGH, SunSensitivity.from("high"))
        assertEquals(SunSensitivity.MODERATE, SunSensitivity.from(null))
        assertEquals(SunSensitivity.MODERATE, SunSensitivity.from("bogus"))
    }

    @Test
    fun `person survives an entity round-trip with all preferences set`() {
        val person = Person(
            id = "p1",
            name = "Alex",
            heatToleranceMaxF = 100,
            coldToleranceMinF = 40,
            uvMax = 8,
            windMaxMph = 20,
            maxPrecipitationPct = 30,
            sunSensitivity = SunSensitivity.HIGH,
            activityPreferences = "hiking, no crowds",
            isArchived = true,
            sortOrder = 3,
            createdAt = "2026-07-19T00:00:00Z"
        )
        assertEquals(person, person.toEntity().toModel())
    }

    @Test
    fun `nullable preferences round-trip as null`() {
        val person = Person(id = "p2", name = "Sam", createdAt = "2026-07-19T00:00:00Z")
        val back = person.toEntity().toModel()
        assertNull(back.heatToleranceMaxF)
        assertNull(back.uvMax)
        assertNull(back.activityPreferences)
        assertEquals(SunSensitivity.MODERATE, back.sunSensitivity)
    }
}
