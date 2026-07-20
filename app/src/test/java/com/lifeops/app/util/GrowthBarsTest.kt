package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class GrowthBarsTest {

    private fun aspects(vararg ids: Pair<String, String>) =
        ids.map { GrowthRings.AspectRef(it.first, it.first.uppercase(), it.second) }

    @Test
    fun `one bar per week, oldest first`() {
        val a = aspects("a" to "#1E88E5")
        val weeks = listOf(
            GrowthRings.WeekInput("w1", "2026-01-05", mapOf("a" to 3.0)),
            GrowthRings.WeekInput("w2", "2026-01-12", mapOf("a" to 8.0))
        )
        val chart = GrowthBars.computeChart(a, weeks)
        assertEquals(listOf("w1", "w2"), chart.bars.map { it.weekId })
    }

    @Test
    fun `segments follow the stable aspect order and total the week's hours`() {
        val a = aspects("a" to "#1E88E5", "b" to "#43A047", "c" to "#FF6D00")
        val week = GrowthRings.WeekInput("w", "2026-01-05", mapOf("a" to 10.0, "b" to 30.0, "c" to 5.0))
        val bar = GrowthBars.computeChart(a, listOf(week)).bars.single()
        assertFalse(bar.isScar)
        assertEquals(listOf("a", "b", "c"), bar.segments.map { it.aspectId })
        assertEquals(45.0, bar.totalHours, 1e-9)
        assertEquals(45.0, bar.segments.sumOf { it.hours }, 1e-9)
    }

    @Test
    fun `zero-hour aspect produces no segment`() {
        val a = aspects("a" to "#1E88E5", "b" to "#43A047")
        val week = GrowthRings.WeekInput("w", "d", mapOf("a" to 12.0, "b" to 0.0))
        val bar = GrowthBars.computeChart(a, listOf(week)).bars.single()
        assertEquals(1, bar.segments.size)
        assertEquals("a", bar.segments.single().aspectId)
    }

    @Test
    fun `zero-hour week is a grey scar bar`() {
        val a = aspects("a" to "#1E88E5")
        val week = GrowthRings.WeekInput("w", "d", emptyMap())
        val bar = GrowthBars.computeChart(a, listOf(week)).bars.single()
        assertTrue(bar.isScar)
        assertEquals(0.0, bar.totalHours, 1e-9)
        assertEquals(GrowthBars.SCAR_COLOR, bar.segments.single().colorHex)
        assertNull(bar.segments.single().aspectId)
    }

    @Test
    fun `maxHours is the tallest bar total and never below 1`() {
        val a = aspects("a" to "#1E88E5")
        val weeks = listOf(
            GrowthRings.WeekInput("w1", "d1", mapOf("a" to 12.0)),
            GrowthRings.WeekInput("w2", "d2", mapOf("a" to 40.0))
        )
        assertEquals(40.0, GrowthBars.computeChart(a, weeks).maxHours, 1e-9)
        // an all-scar record still yields a positive scale so bars can render.
        val scars = listOf(GrowthRings.WeekInput("w", "d", emptyMap()))
        assertEquals(1.0, GrowthBars.computeChart(a, scars).maxHours, 1e-9)
    }

    @Test
    fun `colour by hours matches the ring intensity, off uses flat colour`() {
        val a = aspects("a" to "#1E88E5")
        val week = GrowthRings.WeekInput("w", "d", mapOf("a" to 90.0))
        val intensified = GrowthBars.computeChart(a, listOf(week), colorByHours = true).bars.single().segments.single()
        assertEquals(GrowthColor.intensify("#1E88E5", 90.0), intensified.colorHex)
        val flat = GrowthBars.computeChart(a, listOf(week), colorByHours = false).bars.single().segments.single()
        assertEquals("#1E88E5", flat.colorHex)
    }

    @Test
    fun `bars and rings agree on which weeks are scars`() {
        val a = aspects("a" to "#1E88E5", "b" to "#43A047")
        val weeks = listOf(
            GrowthRings.WeekInput("w1", "d1", mapOf("a" to 5.0)),
            GrowthRings.WeekInput("w2", "d2", emptyMap()),
            GrowthRings.WeekInput("w3", "d3", mapOf("b" to 7.0))
        )
        val ringScars = GrowthRings.computeScene(a, weeks).rings.map { it.isScar }
        val barScars = GrowthBars.computeChart(a, weeks).bars.map { it.isScar }
        assertEquals(ringScars, barScars)
    }
}
