package com.health.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document store's decisions. Small ones, but each is a case a real file picker hands over — a
 * PDF the resolver calls `application/octet-stream`, a name with no extension, a scan with no date.
 */
class DocumentsTest {

    @Test
    fun `an extension comes from the file's own name before its claimed type`() {
        // Content resolvers report octet-stream for perfectly ordinary PDFs all the time.
        assertEquals("pdf", Documents.extensionFor("results.pdf", "application/octet-stream"))
    }

    @Test
    fun `a type is used only when the name has no extension to give`() {
        assertEquals("pdf", Documents.extensionFor("results", "application/pdf"))
        assertEquals("jpg", Documents.extensionFor(null, "image/jpeg"))
    }

    @Test
    fun `an unknown type stores the bytes rather than refusing them`() {
        assertEquals("bin", Documents.extensionFor(null, "application/x-who-knows"))
        assertEquals("bin", Documents.extensionFor(null, null))
    }

    @Test
    fun `something that is not really an extension is not treated as one`() {
        // "Dr. Okafor referral" — the tail after the dot is a word, not a file type.
        assertEquals("bin", Documents.extensionFor("Dr. Okafor referral", null))
    }

    @Test
    fun `a starting title is the file's own name, tidied but not rewritten`() {
        assertEquals("AVS 2026 03 14", Documents.titleFrom("AVS_2026-03-14.pdf"))
        assertEquals("results", Documents.titleFrom("results.pdf"))
        assertNull(Documents.titleFrom(null))
        assertNull(Documents.titleFrom("   "))
    }

    @Test
    fun `only pictures are treated as pictures`() {
        assertTrue(Documents.isImage("image/jpeg"))
        assertTrue(Documents.isImage("IMAGE/PNG"))
        assertFalse(Documents.isImage("application/pdf"))
        assertFalse(Documents.isImage(null))
    }

    @Test
    fun `a size nobody recorded says nothing rather than zero bytes`() {
        assertNull(Documents.formatSize(null))
        assertNull(Documents.formatSize(0))
        assertEquals("512 bytes", Documents.formatSize(512))
        assertTrue(Documents.formatSize(2048)!!.endsWith("KB"))
        assertTrue(Documents.formatSize(5L * 1024 * 1024)!!.endsWith("MB"))
    }

    @Test
    fun `documents read newest first by the date on the document`() {
        data class Row(val title: String, val date: String?)

        val sorted = Documents.sort(
            listOf(
                Row("Old lab", "2024-01-04"),
                Row("Undated scan", null),
                Row("Recent lab", "2026-02-01")
            ),
            date = { it.date },
            title = { it.title }
        )
        // Undated last: a real record, but not one anybody can place in a sequence.
        assertEquals(listOf("Recent lab", "Old lab", "Undated scan"), sorted.map { it.title })
    }

    @Test
    fun `a row is only ever allowed to name a bare file`() {
        assertTrue(Documents.isSafeFileName("a3f2.pdf"))
        assertFalse(Documents.isSafeFileName("../../etc/passwd"))
        assertFalse(Documents.isSafeFileName("nested/file.pdf"))
        assertFalse(Documents.isSafeFileName("windows\\file.pdf"))
        assertFalse(Documents.isSafeFileName(" "))
        assertFalse(Documents.isSafeFileName(null))
    }

    @Test
    fun `the descriptor reports only what was recorded`() {
        val full = DocumentFacts(
            id = "1",
            title = "Bloods",
            kind = DocumentKind.LAB,
            profileId = "p1",
            documentDate = "2026-03-14",
            mimeType = "application/pdf",
            sizeBytes = 2L * 1024 * 1024
        )
        assertEquals("Lab or test result · 14 March 2026 · 2.0 MB", full.descriptor)

        val bare = full.copy(documentDate = null, sizeBytes = null)
        assertEquals("Lab or test result", bare.descriptor)
    }

    @Test
    fun `a household document belongs to nobody rather than to whoever was selected`() {
        val statement = DocumentFacts(
            id = "1",
            title = "Statement",
            kind = DocumentKind.BILL,
            profileId = null,
            documentDate = "2026-03",
            mimeType = "application/pdf",
            sizeBytes = null
        )
        assertNull(statement.profileId)
        assertEquals("Bill or statement · March 2026", statement.descriptor)
    }
}
