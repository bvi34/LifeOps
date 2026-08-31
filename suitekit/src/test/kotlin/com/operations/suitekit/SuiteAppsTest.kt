package com.operations.suitekit

import com.operations.backupkit.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiteAppsTest {

    @Test
    fun `every hosted app has a name, a glyph and a colour`() {
        assertEquals(AppId.entries.toSet(), SuiteApps.all.map { it.appId }.toSet())
        SuiteApps.all.forEach { info ->
            assertTrue(info.label.isNotBlank())
            assertTrue(info.tagline.isNotBlank())
            assertTrue(info.iconKey.isNotBlank())
            assertEquals("${info.label} accent must be opaque", 0xFF, SuiteColors.alpha(info.defaultAccent))
        }
    }

    @Test
    fun `apps are told apart by icon and by colour`() {
        assertEquals(SuiteApps.all.size, SuiteApps.all.map { it.iconKey }.toSet().size)
        assertEquals(SuiteApps.all.size, SuiteApps.all.map { it.defaultAccent }.toSet().size)
        assertEquals(SuiteApps.all.size, SuiteApps.all.map { it.label }.toSet().size)
    }

    @Test
    fun `an app that owns its icon's colours declares them, and they are usable as ink`() {
        // LifeOps is the one that does: a purple dial with an amber checkmark, the colours its
        // launcher icon has always worn (res/values/colors.xml icon_dial / icon_check).
        val lifeOps = SuiteApps.of(AppId.LIFEOPS).iconColors
        assertNotNull("LifeOps keeps its own icon colours", lifeOps)
        assertEquals(0xFF9B72CFL, lifeOps!!.line)
        assertEquals(0xFFFFB74DL, lifeOps.highlight)

        SuiteApps.all.forEach { info ->
            val colours = info.iconColors ?: return@forEach
            assertEquals("${info.label} icon line must be opaque", 0xFF, SuiteColors.alpha(colours.line))
            assertEquals("${info.label} icon highlight must be opaque", 0xFF, SuiteColors.alpha(colours.highlight))
            // Two colours that match are one colour, and one colour is what the accent already does.
            assertNotEquals("${info.label} icon colours must differ", colours.line, colours.highlight)
        }
    }

    @Test
    fun `owning icon colours is the exception - most marks are tinted with the app's accent`() {
        assertTrue(SuiteApps.all.count { it.iconColors != null } < SuiteApps.all.size)
    }

    @Test
    fun `lookup keys off AppId, never off order`() {
        assertEquals("LifeOps", SuiteApps.of(AppId.LIFEOPS).label)
        assertEquals(AppId.HEALTH, SuiteApps.byKey("health")?.appId)
        assertNotNull(SuiteApps.byKey(AppId.ADVISOR.key))
        assertNull(SuiteApps.byKey("not-an-app"))
    }
}
