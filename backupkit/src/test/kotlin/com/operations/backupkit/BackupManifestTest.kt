package com.operations.backupkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

    @Test
    fun `manifest round-trips through the codec`() {
        val manifest = BackupManifest(
            createdAt = 1_700_000_000_000L,
            sandboxVersion = "1.0",
            apps = listOf(
                AppEntry(AppId.LIFEOPS.key, "LifeOps", 14, listOf("data.json")),
                AppEntry(AppId.CITATION.key, "Citation", 3, listOf("citation.db", "sovereign/book.epub"))
            )
        )
        val decoded = BackupCodec.fromJson(BackupCodec.toJson(manifest))
        assertEquals(manifest, decoded)
    }

    @Test
    fun `app lookup keys off AppId, not order`() {
        val manifest = BackupManifest(
            createdAt = 0L,
            sandboxVersion = "x",
            apps = listOf(AppEntry(AppId.CITATION.key, "Citation", 3, emptyList()))
        )
        assertEquals("Citation", manifest.app(AppId.CITATION)?.displayName)
        assertNull(manifest.app(AppId.LIFEOPS))
    }

    @Test
    fun `malformed json parses to null instead of throwing`() {
        assertNull(BackupCodec.fromJson("not json"))
        assertNull(BackupCodec.fromJson(""))
    }

    @Test
    fun `unknown app key resolves to null AppId without crashing`() {
        assertNull(AppId.fromKey("weather-station"))
        assertTrue(AppId.fromKey("lifeops") == AppId.LIFEOPS)
    }
}
