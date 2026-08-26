package com.operations.suitekit

import com.operations.backupkit.AppId
import org.junit.Assert.assertEquals
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
    fun `lookup keys off AppId, never off order`() {
        assertEquals("LifeOps", SuiteApps.of(AppId.LIFEOPS).label)
        assertEquals(AppId.HEALTH, SuiteApps.byKey("health")?.appId)
        assertNotNull(SuiteApps.byKey(AppId.ADVISOR.key))
        assertNull(SuiteApps.byKey("not-an-app"))
    }
}
