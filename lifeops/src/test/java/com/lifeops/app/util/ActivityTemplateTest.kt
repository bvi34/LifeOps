package com.lifeops.app.util

import com.lifeops.app.data.model.ActivityTemplate
import org.junit.Assert.*
import org.junit.Test

class ActivityTemplateTest {

    @Test
    fun `toRequirement copies weather defaults onto the task`() {
        val mowing = ActivityTemplate(
            id = "builtin-mowing", name = "Mowing",
            outdoorPreferred = true, durationMinutes = 90,
            maxTempF = 90, minTempF = 45, avoidRain = true, maxWindMph = 20,
            isBuiltIn = true, sortOrder = 1, createdAt = "2026-01-01T00:00:00Z"
        )
        val req = mowing.toRequirement("task-42")
        assertEquals("task-42", req.taskId)
        assertTrue(req.outdoorPreferred)
        assertEquals(90, req.durationMinutes)
        assertEquals(90, req.maxTempF)
        assertEquals(45, req.minTempF)
        assertTrue(req.avoidRain)
        assertEquals(20, req.maxWindMph)
        assertFalse(req.isEmpty)
    }

    @Test
    fun `template survives an entity round-trip`() {
        val custom = ActivityTemplate(
            id = "c1", name = "Kite Flying",
            outdoorPreferred = true, durationMinutes = null,
            maxTempF = 95, minTempF = null, avoidRain = true, maxWindMph = null,
            isBuiltIn = false, sortOrder = 1001, createdAt = "2026-07-19T00:00:00Z"
        )
        assertEquals(custom, custom.toEntity().toModel())
    }
}
