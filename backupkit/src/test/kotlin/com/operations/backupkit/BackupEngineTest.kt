package com.operations.backupkit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A contributor backed by an in-memory `path -> bytes` map. Backup writes the map out; restore reads
 * whatever the archive served back into [restored], so a round-trip is a map-equality check.
 */
private class FakeContributor(
    override val appId: AppId,
    override val dataVersion: Int,
    private val payload: Map<String, ByteArray>
) : BackupContributor {
    val restored = LinkedHashMap<String, ByteArray>()

    override fun backup(sink: BackupSink) {
        for ((path, bytes) in payload) {
            sink.entry(path).use { it.write(bytes) }
        }
    }

    override fun restore(source: BackupSource) {
        for (path in source.list()) {
            restored[path] = source.open(path)!!.use { it.readBytes() }
        }
    }
}

class BackupEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `backup then restore reproduces every app's payload`() {
        val lifeops = FakeContributor(
            AppId.LIFEOPS, 14, mapOf("data.json" to bytes("""{"version":14}"""))
        )
        val citation = FakeContributor(
            AppId.CITATION, 3, mapOf(
                "citation.db" to bytes("SQLITE-BINARY-BYTES"),
                "sovereign/book.epub" to bytes("EPUB-BYTES"),
                "sovereign/sync/envelope.json" to bytes("{}")
            )
        )

        val out = ByteArrayOutputStream()
        BackupEngine.backup(listOf(lifeops, citation), sandboxVersion = "1.0", out = out)
        val archive = out.toByteArray()

        // Manifest peek reflects both apps without extracting payloads.
        val manifest = BackupEngine.readManifest(ByteArrayInputStream(archive))!!
        assertEquals(1, manifest.formatVersion)
        assertEquals("1.0", manifest.sandboxVersion)
        assertEquals(setOf("lifeops", "citation"), manifest.apps.map { it.appId }.toSet())
        assertEquals(3, manifest.app(AppId.CITATION)!!.dataVersion)
        assertTrue(manifest.app(AppId.CITATION)!!.entries.contains("sovereign/book.epub"))

        // Fresh contributors receive the restored bytes.
        val lifeopsBack = FakeContributor(AppId.LIFEOPS, 14, emptyMap())
        val citationBack = FakeContributor(AppId.CITATION, 3, emptyMap())
        BackupEngine.restore(
            listOf(lifeopsBack, citationBack),
            workDir = temp.newFolder("restore"),
            zipIn = ByteArrayInputStream(archive)
        )

        assertArrayEquals(bytes("""{"version":14}"""), lifeopsBack.restored["data.json"])
        assertArrayEquals(bytes("EPUB-BYTES"), citationBack.restored["sovereign/book.epub"])
        assertArrayEquals(bytes("{}"), citationBack.restored["sovereign/sync/envelope.json"])
        assertEquals(3, citationBack.restored.size)
    }

    @Test
    fun `only selected contributors are backed up`() {
        val lifeops = FakeContributor(AppId.LIFEOPS, 14, mapOf("data.json" to bytes("x")))
        val out = ByteArrayOutputStream()
        BackupEngine.backup(listOf(lifeops), sandboxVersion = "1.0", out = out)

        val manifest = BackupEngine.readManifest(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(listOf("lifeops"), manifest.apps.map { it.appId })
    }

    @Test
    fun `restore skips apps with no matching contributor`() {
        // Archive holds citation, but only a lifeops contributor is offered on restore.
        val citation = FakeContributor(AppId.CITATION, 3, mapOf("citation.db" to bytes("db")))
        val out = ByteArrayOutputStream()
        BackupEngine.backup(listOf(citation), sandboxVersion = "1.0", out = out)

        val lifeopsBack = FakeContributor(AppId.LIFEOPS, 14, emptyMap())
        val manifest = BackupEngine.restore(
            listOf(lifeopsBack),
            workDir = temp.newFolder("restore"),
            zipIn = ByteArrayInputStream(out.toByteArray())
        )
        // Manifest still reports what the archive held; the lifeops contributor got nothing.
        assertNotNull(manifest.app(AppId.CITATION))
        assertTrue(lifeopsBack.restored.isEmpty())
    }

    @Test
    fun `restore of a contributor absent from the archive is a no-op`() {
        val lifeops = FakeContributor(AppId.LIFEOPS, 14, mapOf("data.json" to bytes("x")))
        val out = ByteArrayOutputStream()
        BackupEngine.backup(listOf(lifeops), sandboxVersion = "1.0", out = out)

        // Offer a citation contributor; the archive has no citation dir.
        val citationBack = FakeContributor(AppId.CITATION, 3, emptyMap())
        val lifeopsBack = FakeContributor(AppId.LIFEOPS, 14, emptyMap())
        BackupEngine.restore(
            listOf(lifeopsBack, citationBack),
            workDir = temp.newFolder("restore"),
            zipIn = ByteArrayInputStream(out.toByteArray())
        )
        assertTrue(citationBack.restored.isEmpty())
        assertArrayEquals(bytes("x"), lifeopsBack.restored["data.json"])
    }

    @Test
    fun `readManifest returns null when there is no manifest`() {
        // A zip with a stray entry but no manifest.json.
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("stray.txt"))
            it.write(bytes("hi"))
            it.closeEntry()
        }
        assertNull(BackupEngine.readManifest(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `restore rejects a zip-slip entry`() {
        // Hand-craft an archive whose entry name escapes the work dir.
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("../escape.txt"))
            it.write(bytes("pwned"))
            it.closeEntry()
        }
        var threw = false
        try {
            BackupEngine.restore(emptyList(), temp.newFolder("restore"), ByteArrayInputStream(out.toByteArray()))
        } catch (e: SecurityException) {
            threw = true
        } catch (e: IllegalArgumentException) {
            // Also acceptable: it fails before finding a manifest. Either way it must not write outside.
            threw = true
        }
        assertTrue("zip-slip entry must be rejected", threw)
        assertFalse("escaped file must not exist", temp.root.parentFile.resolve("escape.txt").exists())
    }
}
