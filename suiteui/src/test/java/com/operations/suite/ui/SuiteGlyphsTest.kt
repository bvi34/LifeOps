package com.operations.suite.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.operations.suitekit.SuiteApps
import org.junit.Assert.assertEquals
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
