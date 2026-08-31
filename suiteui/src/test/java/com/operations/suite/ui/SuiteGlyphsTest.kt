package com.operations.suite.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.operations.suitekit.SuiteApps
import com.operations.suitekit.SuiteIconColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen's icons are the one place where a wiring mistake is invisible in code review and
 * obvious to a user: an app whose `iconKey` nobody drew a mark for still compiles, still lays out,
 * and just quietly wears the fallback house next to six apps that don't.
 *
 * These are JVM tests despite living in an Android module, which SuiteGlyphs earns by touching no
 * Android API — an ImageVector is data until something draws it.
 */
class SuiteGlyphsTest {

    private fun paths(vector: ImageVector): List<VectorPath> {
        fun walk(group: VectorGroup): List<VectorPath> = group.flatMap { node ->
            when (node) {
                is VectorPath -> listOf(node)
                is VectorGroup -> walk(node)
                else -> emptyList()
            }
        }
        return walk(vector.root)
    }

    @Test
    fun `every hosted app has a mark of its own, never the fallback`() {
        SuiteApps.all.forEach { info ->
            assertTrue(
                "${info.label} (iconKey '${info.iconKey}') has no mark — it would fall back",
                SuiteGlyphs.byKey.containsKey(info.iconKey)
            )
        }
    }

    @Test
    fun `no mark is drawn for an app that does not exist`() {
        assertEquals(SuiteApps.all.map { it.iconKey }.toSet(), SuiteGlyphs.byKey.keys)
    }

    @Test
    fun `every mark actually draws something`() {
        SuiteGlyphs.byKey.forEach { (key, vector) ->
            val drawn = paths(vector)
            assertTrue("'$key' has no paths at all", drawn.isNotEmpty())
            assertTrue("'$key' has an empty path", drawn.all { it.pathData.isNotEmpty() })
        }
    }

    @Test
    fun `every mark shares the same viewport, so weights compare across apps`() {
        SuiteGlyphs.byKey.forEach { (key, vector) ->
            assertEquals("'$key' viewport width", 24f, vector.viewportWidth, 0f)
            assertEquals("'$key' viewport height", 24f, vector.viewportHeight, 0f)
        }
    }

    @Test
    fun `no two apps wear the same drawing`() {
        // The point of the set is that a tile is recognisable before its colour registers, which
        // two apps sharing geometry would quietly undo.
        val shapes = SuiteGlyphs.byKey.mapValues { (_, vector) -> paths(vector).map { it.pathData } }
        assertEquals(SuiteGlyphs.byKey.size, shapes.values.toSet().size)
        assertEquals(SuiteGlyphs.byKey.size, SuiteGlyphs.byKey.values.map { it.name }.toSet().size)
    }

    @Test
    fun `a mark built in an app's own icon colours is the same drawing, wearing them`() {
        val colours = SuiteIconColors(line = 0xFF9B72CFL, highlight = 0xFFFFB74DL)
        val coloured = SuiteGlyphs.inColour("lifeops-dial", colours)
        assertNotNull("LifeOps' mark can be drawn in its own colours", coloured)

        // Same geometry as the tintable form — the colours are the only difference, so the two
        // cannot drift into being different drawings.
        val drawn = paths(coloured!!)
        assertEquals(paths(SuiteGlyphs.Dial).map { it.pathData }, drawn.map { it.pathData })

        // Ring and ticks in the dial colour, the checkmark needle in the check colour.
        assertEquals(
            listOf(Color(colours.line), Color(colours.line), Color(colours.highlight)),
            drawn.map { (it.stroke as SolidColor).value }
        )
    }

    @Test
    fun `every app's mark can be drawn in colours of its own, not just the ones that ship them`() {
        // The sandbox settings can give any app an icon-colour pair, so a mark that only had a
        // tintable form would let that setting silently do nothing for seven of the eight apps.
        val colours = SuiteIconColors(line = 0xFF9B72CFL, highlight = 0xFFFFB74DL)
        SuiteApps.all.forEach { info ->
            val mark = SuiteGlyphs.inColour(info.iconKey, colours)
            assertNotNull("${info.label} has no two-colour form", mark)
            // Both roles have to be used, or one of the two colours is a setting with no effect.
            val inks = paths(mark!!).map { (it.stroke as? SolidColor ?: it.fill as SolidColor).value }
            assertTrue("${info.label} draws nothing in its line colour", inks.contains(Color(colours.line)))
            assertTrue(
                "${info.label} draws nothing in its highlight colour",
                inks.contains(Color(colours.highlight))
            )
        }
        assertNull(SuiteGlyphs.inColour("not-an-icon", colours))
    }

    @Test
    fun `LifeOps keeps its own launcher mark - a dial, four ticks and a checkmark needle`() {
        // Not a new drawing: the mark LifeOps already shipped, redrawn at icon scale. The ring is
        // deliberately the faintest thing in it and the needle the boldest, as in the original.
        val dial = paths(SuiteGlyphs.Dial)
        assertEquals(3, dial.size)
        val (ring, ticks, needle) = Triple(dial[0], dial[1], dial[2])
        assertTrue("the ring should stay faint", ring.strokeAlpha < 1f)
        assertTrue("ticks heavier than the ring", ticks.strokeLineWidth > ring.strokeLineWidth)
        assertTrue("the needle heaviest of all", needle.strokeLineWidth > ticks.strokeLineWidth)
    }
}
