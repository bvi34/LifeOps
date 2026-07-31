package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class ImportParserTest {

    // ── computeResourceValue ──────────────────────────────────────────────

    @Test
    fun `medium priority 60 min no deadline gives 10 pts`() {
        assertEquals(10, ImportParser.computeResourceValue("medium", false, 60))
    }

    @Test
    fun `critical priority with hard deadline 60 min`() {
        // 1.5 urgency + 0.25 hard deadline = 1.75x; 10 * 1.75 = 17.5 → rounds to 18
        assertEquals(18, ImportParser.computeResourceValue("critical", true, 60))
    }

    @Test
    fun `low priority 30 min no deadline`() {
        // base = 30/6 = 5; 5 * 0.75 = 3.75 → 4
        assertEquals(4, ImportParser.computeResourceValue("low", false, 30))
    }

    @Test
    fun `manually added task gets 0_5x multiplier`() {
        val imported = ImportParser.computeResourceValue("medium", false, 60, isManuallyAdded = false)
        val manual = ImportParser.computeResourceValue("medium", false, 60, isManuallyAdded = true)
        assertEquals(imported / 2, manual)
    }

    @Test
    fun `task over 60 min uses hourly scoring`() {
        // 120 min: 10 + (60/60) = 11; medium 1x = 11
        assertEquals(11, ImportParser.computeResourceValue("medium", false, 120))
    }

    @Test
    fun `minimum result is 1 pt`() {
        // 1 min low priority manually added: base=1, 1*0.75*0.5 = 0.375 → rounds to 0 → coerced to 1
        assertEquals(1, ImportParser.computeResourceValue("low", false, 1, isManuallyAdded = true))
    }

    // ── parse ─────────────────────────────────────────────────────────────

    @Test
    fun `parse tasks array at root`() {
        val json = """[{"title":"Task A","priority":"high"}]"""
        val result = ImportParser.parse(json)
        assertNull(result.error)
        assertEquals(1, result.tasks.size)
        assertEquals("Task A", result.tasks[0].title)
        assertEquals("high", result.tasks[0].priority)
    }

    @Test
    fun `parse tasks wrapped in object`() {
        val json = """{"tasks":[{"title":"Task B","priority":"low"}]}"""
        val result = ImportParser.parse(json)
        assertNull(result.error)
        assertEquals(1, result.tasks.size)
        assertEquals("Task B", result.tasks[0].title)
    }

    @Test
    fun `missing title returns error`() {
        val json = """[{"priority":"medium"}]"""
        val result = ImportParser.parse(json)
        assertNotNull(result.error)
        assertTrue(result.tasks.isEmpty())
    }

    @Test
    fun `unknown fields captured`() {
        val json = """[{"title":"T","priority":"medium","custom_field":"foo"}]"""
        val result = ImportParser.parse(json)
        assertNull(result.error)
        assertEquals(mapOf("custom_field" to "foo"), result.tasks[0].unknownFields)
    }

    @Test
    fun `defaults applied when fields missing`() {
        val json = """[{"title":"T"}]"""
        val task = ImportParser.parse(json).tasks[0]
        assertEquals("medium", task.priority)
        assertEquals("pending", task.status)
        assertFalse(task.hardDeadline)
        assertFalse(task.isRecurring)
    }
}
