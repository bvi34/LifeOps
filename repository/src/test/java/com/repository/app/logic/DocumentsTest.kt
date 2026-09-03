package com.repository.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules about a file, at the moment it is being taken in.
 *
 * All of them are about the store handing back exactly what it was given — the right name, the right
 * extension, and nothing that could walk out of the directory it was put in.
 */
class DocumentsTest {

    @Test
    fun `a size is read at a glance or not at all`() {
        assertEquals("512 bytes", Documents.formatSize(512))
        assertEquals("400 KB", Documents.formatSize(400_000))
        assertEquals("1.2 MB", Documents.formatSize(1_200_000))
        assertEquals("12.0 MB", Documents.formatSize(12_000_000))
        // An unknown size is not zero, and a nonsense one is not a number to show.
        assertNull(Documents.formatSize(null))
        assertNull(Documents.formatSize(-1))
    }

    @Test
    fun `the file's own extension wins, because that is the file you were given`() {
        assertEquals("pdf", Documents.extensionFor("statement.pdf", "application/octet-stream"))
        assertEquals("docx", Documents.extensionFor("Lease agreement.DOCX", null))
    }

    @Test
    fun `with no name to go on, the type is mapped - and anything unknown is honest about it`() {
        assertEquals("pdf", Documents.extensionFor(null, "application/pdf"))
        assertEquals("jpg", Documents.extensionFor(null, "image/jpeg"))
        assertEquals("csv", Documents.extensionFor("statement", "text/csv; charset=utf-8"))
        // `bin` rather than a guess: the row keeps the real MIME type, and this only names a file.
        assertEquals("bin", Documents.extensionFor(null, "application/x-something"))
        assertEquals("bin", Documents.extensionFor(null, null))
    }

    @Test
    fun `a document leaves under its title, made safe for a file system it cannot see`() {
        // One rule for both roads out — the share sheet's copy and the copy written into a folder on
        // a drive — so a document has one name wherever it lands.
        assertEquals("Mortgage statement March 2026.pdf", Documents.exportFileName("Mortgage statement March 2026", "pdf"))
        assertEquals("Deed- 2026-03-11.pdf", Documents.exportFileName("Deed: 2026/03/11", "pdf"))
        assertEquals("document.bin", Documents.exportFileName("   ", ""))
        // Blunter than any one file system requires, deliberately: the destination may be FAT on an
        // SD card or a cloud provider with rules of its own.
        assertEquals("Q3 budget.xlsx", Documents.exportFileName("Q3  budget", "xlsx"))
    }

    @Test
    fun `a suspicious extension is not taken from a name`() {
        // Long or punctuated tails are not extensions; they are the rest of a sentence.
        assertEquals("bin", Documents.extensionFor("notes.this-is-not-an-extension", null))
        assertEquals("pdf", Documents.extensionFor("2026.03.statement.pdf", null))
    }

    @Test
    fun `a title is cleaned up and never guessed at`() {
        assertEquals("2026 03 statement", Documents.titleFrom("2026-03-statement.pdf"))
        assertEquals("Roof survey", Documents.titleFrom("Roof_survey.PDF"))
        assertEquals("scan 0041", Documents.titleFrom("scan 0041"))
        assertEquals("", Documents.titleFrom(null))
        // Deliberately not clever: a wrong guess in a filled-in field gets accepted, an ugly one
        // gets corrected.
        assertEquals("IMG 20260214 093122", Documents.titleFrom("IMG_20260214_093122.jpg"))
    }

    @Test
    fun `a stored name is a bare file name or it is refused`() {
        assertTrue(Documents.isSafeFileName("6f1c-4a2e.pdf"))
        assertFalse(Documents.isSafeFileName("../../databases/health.db"))
        assertFalse(Documents.isSafeFileName("nested/file.pdf"))
        assertFalse(Documents.isSafeFileName("windows\\file.pdf"))
        assertFalse(Documents.isSafeFileName(".."))
        assertFalse(Documents.isSafeFileName(" "))
        assertFalse(Documents.isSafeFileName(null))
    }

    @Test
    fun `a picture is the one thing storage treats differently`() {
        assertTrue(Documents.isImage("image/jpeg"))
        assertTrue(Documents.isImage("IMAGE/HEIC"))
        // A PDF is the document itself — the practice's own file, with the letterhead on it — and is
        // copied byte for byte. See `data/store/DocumentFiles`.
        assertFalse(Documents.isImage("application/pdf"))
        assertFalse(Documents.isImage(null))
    }

    @Test
    fun `every kind survives a round trip, and an unknown one files as Other`() {
        DocumentKind.entries.forEach { kind ->
            assertEquals(kind, DocumentKind.fromKey(kind.key))
            assertTrue(kind.label.isNotBlank())
        }
        assertEquals(DocumentKind.OTHER, DocumentKind.fromKey("a-kind-a-later-build-invented"))
        assertEquals(DocumentKind.OTHER, DocumentKind.fromKey(null))
        // Keys are what a row stores; two kinds sharing one would silently merge them.
        assertEquals(DocumentKind.entries.size, DocumentKind.entries.map { it.key }.toSet().size)
    }
}
