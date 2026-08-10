package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorPermissionsTest {

    private fun doc(source: SourceApp) =
        KnowledgeDocument("${source.key}:test:1", source, "test", "t", "b")

    @Test
    fun default_denies_everything() {
        val perms = AdvisorPermissions.NONE
        assertTrue(perms.isEmpty)
        SourceApp.entries.forEach { assertFalse(perms.isGranted(it)) }
    }

    @Test
    fun grant_and_revoke_are_immutable() {
        val base = AdvisorPermissions.NONE
        val granted = base.grant(SourceApp.LIFEOPS)
        assertFalse("original unchanged", base.isGranted(SourceApp.LIFEOPS))
        assertTrue(granted.isGranted(SourceApp.LIFEOPS))
        assertFalse(granted.revoke(SourceApp.LIFEOPS).isGranted(SourceApp.LIFEOPS))
    }

    @Test
    fun with_toggles_both_directions() {
        val perms = AdvisorPermissions.NONE
            .with(SourceApp.CITATION, true)
            .with(SourceApp.LOGISTICS, true)
            .with(SourceApp.CITATION, false)
        assertFalse(perms.isGranted(SourceApp.CITATION))
        assertTrue(perms.isGranted(SourceApp.LOGISTICS))
    }

    @Test
    fun filter_keeps_only_granted_sources() {
        val perms = AdvisorPermissions.NONE.grant(SourceApp.LIFEOPS)
        val docs = listOf(doc(SourceApp.LIFEOPS), doc(SourceApp.CITATION), doc(SourceApp.LOGISTICS))
        val filtered = perms.filter(docs)
        assertEquals(1, filtered.size)
        assertEquals(SourceApp.LIFEOPS, filtered.first().source)
    }

    @Test
    fun all_grants_every_source() {
        assertEquals(SourceApp.entries.size, AdvisorPermissions.ALL.granted.size)
    }
}
