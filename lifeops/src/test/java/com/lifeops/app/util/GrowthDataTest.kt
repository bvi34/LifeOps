package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class GrowthDataTest {

    private fun sealed(weekId: String, date: String, hist: Map<String, GrowthData.AspectHist>) =
        GrowthData.WeekSource(weekId, date, isClosed = true, aspectHistory = hist, liveMinutesByAspect = emptyMap())

    private fun open(weekId: String, date: String, live: Map<String, Int>) =
        GrowthData.WeekSource(weekId, date, isClosed = false, aspectHistory = emptyMap(), liveMinutesByAspect = live)

    @Test
    fun `sealed snapshot wins, open week falls back to live`() {
        val live = listOf(GrowthData.LiveAspect("b", "Body", "#43A047"))
        val weeks = listOf(
            sealed("w1", "2026-01-05", mapOf("b" to GrowthData.AspectHist(1800, "Body", "#43A047"))), // 30h sealed
            open("w2", "2026-01-12", mapOf("b" to 1200)) // 20h live
        )
        val a = GrowthData.assemble(live, weeks)
        assertEquals(30.0, a.weeks.first { it.weekId == "w1" }.hoursByAspect["b"]!!, 1e-9)
        assertEquals(20.0, a.weeks.first { it.weekId == "w2" }.hoursByAspect["b"]!!, 1e-9)
    }

    /**
     * The guard requested at review time: deleting an aspect must NOT erase its historical
     * rings. Because the snapshot blob is keyed by id and carries name+colour, a sealed week
     * still renders the deleted aspect faithfully even though it's gone from the live list.
     */
    @Test
    fun `deleteAspect preservesHistoricalSnapshots`() {
        // 'a' (Art) has been deleted — only 'b' remains live. Its task links were SET NULL,
        // so live minutes are empty; only the sealed snapshot remembers it.
        val live = listOf(GrowthData.LiveAspect("b", "Body", "#43A047"))
        val weeks = listOf(
            sealed(
                "w1", "2026-01-05",
                mapOf(
                    "a" to GrowthData.AspectHist(600, "Art", "#FF6D00"),
                    "b" to GrowthData.AspectHist(1800, "Body", "#43A047")
                )
            )
        )
        val a = GrowthData.assemble(live, weeks)

        val art = a.aspects.firstOrNull { it.id == "a" }
        assertNotNull("deleted aspect kept in order", art)
        assertEquals("Art", art!!.name)
        assertEquals("#FF6D00", art.colorHex)

        val scene = GrowthRings.computeScene(a.aspects, a.weeks)
        val ring = scene.rings.single { it.weekId == "w1" }
        val fills = ring.bands.filter { it.kind == GrowthRings.BandKind.FILL }
        assertEquals("both aspects rendered", 2, fills.size)
        assertTrue("deleted aspect band present", fills.any { it.aspectId == "a" })
        assertEquals(10.0, a.weeks.single().hoursByAspect["a"]!!, 1e-9) // sealed 600 min
    }

    @Test
    fun `aspect order is stable by id and append-only`() {
        val live = listOf(
            GrowthData.LiveAspect("c", "C", "#FF6D00"),
            GrowthData.LiveAspect("a", "A", "#1E88E5"),
            GrowthData.LiveAspect("b", "B", "#43A047")
        )
        val a = GrowthData.assemble(live, listOf(open("w1", "d", mapOf("a" to 60))))
        assertEquals(listOf("a", "b", "c"), a.aspects.map { it.id })
    }

    @Test
    fun `weeks are ordered chronologically by start date`() {
        val live = listOf(GrowthData.LiveAspect("a", "A", "#1E88E5"))
        val weeks = listOf(
            open("w3", "2026-01-19", mapOf("a" to 60)),
            open("w1", "2026-01-05", mapOf("a" to 60)),
            open("w2", "2026-01-12", mapOf("a" to 60))
        )
        val a = GrowthData.assemble(live, weeks)
        assertEquals(listOf("w1", "w2", "w3"), a.weeks.map { it.weekId })
    }
}
