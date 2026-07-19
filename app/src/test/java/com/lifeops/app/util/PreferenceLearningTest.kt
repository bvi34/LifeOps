package com.lifeops.app.util

import com.lifeops.app.data.model.ActivityOverride
import com.lifeops.app.data.model.ActivityTemplate
import org.junit.Assert.*
import org.junit.Test

class PreferenceLearningTest {

    private fun template(id: String, name: String, maxTempF: Int? = null, minTempF: Int? = null) =
        ActivityTemplate(id = id, name = name, maxTempF = maxTempF, minTempF = minTempF, createdAt = "2026-01-01T00:00:00Z")

    private fun override(activityId: String, field: String, templateValue: Int?, userValue: Int?) =
        ActivityOverride("o-${Math.random()}", activityId, field, templateValue, userValue, "2026-07-19T00:00:00Z")

    @Test
    fun `consistent higher max-temp overrides suggest raising the default`() {
        val templates = listOf(template("a", "Mowing", maxTempF = 90))
        val overrides = listOf(
            override("a", "maxTempF", 90, 95),
            override("a", "maxTempF", 90, 96),
            override("a", "maxTempF", 90, 97)
        )
        val suggestions = PreferenceLearning.suggest(overrides, templates)
        assertEquals(1, suggestions.size)
        val s = suggestions.first()
        assertEquals("Mowing", s.activityName)
        assertEquals("maxTempF", s.field)
        assertEquals(90, s.currentValue)
        assertEquals(96, s.suggestedValue) // median of 95,96,97
        assertTrue(s.message.contains("Mowing"))
    }

    @Test
    fun `fewer than the minimum observations yields nothing`() {
        val templates = listOf(template("a", "Mowing", maxTempF = 90))
        val overrides = listOf(
            override("a", "maxTempF", 90, 95),
            override("a", "maxTempF", 90, 96)
        )
        assertTrue(PreferenceLearning.suggest(overrides, templates).isEmpty())
    }

    @Test
    fun `no suggestion when the median equals the current default`() {
        val templates = listOf(template("a", "Mowing", maxTempF = 90))
        val overrides = listOf(
            override("a", "maxTempF", 85, 90),
            override("a", "maxTempF", 85, 90),
            override("a", "maxTempF", 85, 90)
        )
        assertTrue(PreferenceLearning.suggest(overrides, templates).isEmpty())
    }

    @Test
    fun `consistent lower min-temp overrides suggest lowering the default`() {
        val templates = listOf(template("b", "Camping", minTempF = 40))
        val overrides = listOf(
            override("b", "minTempF", 40, 30),
            override("b", "minTempF", 40, 32),
            override("b", "minTempF", 40, 34)
        )
        val s = PreferenceLearning.suggest(overrides, templates).single()
        assertEquals(32, s.suggestedValue)
        assertEquals(40, s.currentValue)
    }
}
