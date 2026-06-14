package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class GrowthRingsTest {

    private fun aspects(vararg ids: Pair<String, String>) =
        ids.map { GrowthRings.AspectRef(it.first, it.first.uppercase(), it.second) }

    // ---- Phase 3: band proportions ----

    @Test
    fun `active band shares sum to 1 and fill RING_T exactly`() {
        val a = aspects("a" to "#1E88E5", "b" to "#43A047", "c" to "#FF6D00")
        val week = GrowthRings.WeekInput("w", "2026-01-05", mapOf("a" to 10.0, "b" to 30.0, "c" to 5.0))
        val ring = GrowthRings.computeScene(a, listOf(week)).rings.single()
        val fills = ring.bands.filter { it.kind == GrowthRings.BandKind.FILL }
        assertEquals(3, fills.size)
        assertEquals(GrowthRings.R0, fills.first().innerR, 1e-9)
        assertEquals(GrowthRings.R0 + GrowthRings.RING_T, fills.last().outerR, 1e-9)
        for (i in 0 until fills.size - 1) {
            assertEquals(fills[i].outerR, fills[i + 1].innerR, 1e-9)
        }
    }

    @Test
    fun `zero-hour aspect produces no band`() {
        val a = aspects("a" to "#1E88E5", "b" to "#43A047")
        val week = GrowthRings.WeekInput("w", "d", mapOf("a" to 12.0, "b" to 0.0))
        val ring = GrowthRings.computeScene(a, listOf(week)).rings.single()
        val fills = ring.bands.filter { it.kind == GrowthRings.BandKind.FILL }
        assertEquals(1, fills.size)
        assertEquals("a", fills.single().aspectId)
    }

    // ---- Phase 6: scars ----

    @Test
    fun `zero-hour week is a grey scar that still advances the radius`() {
        val a = aspects("a" to "#1E88E5")
        val weeks = listOf(
            GrowthRings.WeekInput("w1", "d1", mapOf("a" to 12.0)),
            GrowthRings.WeekInput("w2", "d2", emptyMap()),
            GrowthRings.WeekInput("w3", "d3", mapOf("a" to 8.0))
        )
        val scene = GrowthRings.computeScene(a, weeks)
        assertTrue(scene.rings[1].isScar)
        assertEquals(GrowthRings.SCAR_COLOR, scene.rings[1].bands.single().colorHex)
        assertEquals(GrowthRings.SCAR_RING_T, scene.rings[1].outerR - scene.rings[1].innerR, 1e-9)
        assertTrue(scene.rings[2].innerR > scene.rings[1].outerR)
    }

    // ---- Phase 4: colour by hours ----

    @Test
    fun `colour mapping at h=0,25,50,90`() {
        val base = "#1E88E5"
        val baseS = GrowthColor.hexToHsl(base).s
        fun satAt(h: Double) = GrowthColor.hexToHsl(GrowthColor.intensify(base, h)).s
        assertEquals("10h muted", (baseS * 0.6).coerceAtMost(1.0), satAt(10.0), 0.02)
        assertEquals("25h", (baseS * 0.75).coerceAtMost(1.0), satAt(25.0), 0.02)
        assertEquals("50h full", baseS, satAt(50.0), 0.02)
        val l50 = GrowthColor.hexToHsl(GrowthColor.intensify(base, 50.0)).l
        val l90 = GrowthColor.hexToHsl(GrowthColor.intensify(base, 90.0)).l
        assertTrue("90h brighter than 50h", l90 > l50)
    }

    @Test
    fun `colour by hours off uses flat aspect colour`() {
        val a = aspects("a" to "#1E88E5")
        val week = GrowthRings.WeekInput("w", "d", mapOf("a" to 90.0))
        val band = GrowthRings.computeScene(a, listOf(week), colorByHours = false)
            .fillBands().single()
        assertEquals("#1E88E5", band.colorHex)
    }

    // ---- Phase 5: glow ----

    @Test
    fun `glow strength bounds`() {
        assertEquals(0.0, GrowthRings.glowStrength(0.0), 1e-9)
        assertEquals(0.0, GrowthRings.glowStrength(50.0), 1e-9)
        assertEquals(0.5, GrowthRings.glowStrength(80.0), 1e-9)
        assertEquals(0.9, GrowthRings.glowStrength(10_000.0), 1e-9)
    }

    @Test
    fun `glow gated on colour-by-hours`() {
        val a = aspects("a" to "#1E88E5")
        val wk = listOf(GrowthRings.WeekInput("w", "d", mapOf("a" to 90.0)))
        assertTrue(GrowthRings.computeScene(a, wk, colorByHours = true, glowEnabled = true).glowBands().isNotEmpty())
        assertTrue(GrowthRings.computeScene(a, wk, colorByHours = false).glowBands().isEmpty())
        assertTrue(GrowthRings.computeScene(a, wk, colorByHours = true, glowEnabled = false).glowBands().isEmpty())
    }

    // ---- THE INVARIANT ----

    @Test
    fun `appending week K+1 leaves rings 1 to K byte-identical`() {
        val a = aspects("a" to "#1E88E5", "b" to "#43A047")
        val weeksK = (1..6).map { i ->
            GrowthRings.WeekInput("w$i", "d$i", mapOf("a" to i * 3.0, "b" to (i % 4) * 5.0))
        }
        val sceneK = GrowthRings.computeScene(a, weeksK)
        val sceneK1 = GrowthRings.computeScene(a, weeksK + GrowthRings.WeekInput("w7", "d7", mapOf("a" to 99.0, "b" to 2.0)))
        assertEquals(sceneK.rings, sceneK1.rings.take(sceneK.rings.size))
    }

    @Test
    fun `adding or renaming an aspect leaves existing renders identical`() {
        val a = aspects("a" to "#1E88E5", "b" to "#43A047")
        val weeks = (1..4).map { i -> GrowthRings.WeekInput("w$i", "d$i", mapOf("a" to i * 2.0, "b" to i * 1.0)) }
        val before = GrowthRings.computeScene(a, weeks)
        val a2 = listOf(
            GrowthRings.AspectRef("a", "Renamed", "#1E88E5"),
            GrowthRings.AspectRef("b", "B", "#43A047"),
            GrowthRings.AspectRef("z-new", "New", "#FDD835")
        )
        assertEquals(before.rings, GrowthRings.computeScene(a2, weeks).rings)
    }

    @Test
    fun `ring K sits at the same radius regardless of total week count`() {
        val a = aspects("a" to "#1E88E5")
        val short = GrowthRings.computeScene(a, (1..3).map { GrowthRings.WeekInput("w$it", "d", mapOf("a" to 5.0)) })
        val long = GrowthRings.computeScene(a, (1..53).map { GrowthRings.WeekInput("w$it", "d", mapOf("a" to 5.0)) })
        assertEquals(short.rings[2].innerR, long.rings[2].innerR, 1e-9)
        assertEquals(short.rings[2].outerR, long.rings[2].outerR, 1e-9)
    }

    // ---- Phase 9: CSV round-trip ----

    @Test
    fun `csv export-import round-trips`() {
        val a = listOf(
            GrowthRings.AspectRef("a", "Mind", "#1E88E5"),
            GrowthRings.AspectRef("b", "Body, Health", "#43A047"), // comma forces quoting
            GrowthRings.AspectRef("c", "Craft", "#FF6D00")
        )
        val weeks = listOf(
            GrowthRings.WeekInput("w1", "2026-01-05", mapOf("a" to 12.0, "b" to 30.0, "c" to 2.5)),
            GrowthRings.WeekInput("w2", "2026-01-12", mapOf("a" to 0.0, "b" to 8.0))
        )
        val parsed = GrowthExport.parseRingsCsv(GrowthExport.buildRingsCsv(a, weeks)).getOrThrow()
        assertEquals(listOf("Mind", "Body, Health", "Craft"), parsed.aspectNames)
        assertEquals(2, parsed.weeks.size)
        assertEquals("2026-01-05", parsed.weeks[0].first)
        assertEquals(listOf(12.0, 30.0, 2.5), parsed.weeks[0].second)
        assertEquals(listOf(0.0, 8.0, 0.0), parsed.weeks[1].second)
    }

    @Test
    fun `bad csv fails in the interface voice`() {
        val r = GrowthExport.parseRingsCsv("nope,1,2\nx,1,2")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.startsWith("Couldn't read"))
    }

    // ---- Phase 9: SVG keeps the glow filter + gradient ----

    @Test
    fun `svg export keeps glow filter and gradient`() {
        val a = aspects("a" to "#1E88E5")
        val svg = GrowthExport.buildSvg(GrowthRings.computeScene(a, listOf(GrowthRings.WeekInput("w", "d", mapOf("a" to 90.0)))))
        assertTrue(svg.contains("feGaussianBlur"))
        assertTrue(svg.contains("stdDeviation=\"4.5\""))
        assertTrue(svg.contains("radialGradient"))
        assertTrue(svg.trimStart().startsWith("<svg"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }
}
