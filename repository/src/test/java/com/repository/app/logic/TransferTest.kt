package com.repository.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grabbing a set of files off a drive, and putting some back.
 *
 * Everything here is about the screen between the picker and the shelf: what it says you are about
 * to file, what it warns you about, and what it says afterwards. None of it touches a file, which is
 * the point — the rules a household will actually notice can be argued about in a test.
 */
class TransferTest {

    private fun item(
        name: String?,
        uri: String = "content://com.microsoft.skydrive.content.StorageAccessProvider/document/$name",
        size: Long? = 1_200_000
    ) = TransferItem(uri = uri, displayName = name, mimeType = "application/pdf", sizeBytes = size)

    @Test
    fun `a picked set arrives titled from its own names, in the order it was picked`() {
        val plan = Transfer.plan(listOf(item("brief-final.pdf"), item("2026-03-contract.pdf")))
        assertEquals(listOf("brief final", "2026 03 contract"), plan.map { it.title })
        // Everything is in until somebody says otherwise: the picker was the choosing.
        assertTrue(plan.all { it.include })
    }

    @Test
    fun `unticking a row takes it out of the count, the size and the filing`() {
        val plan = Transfer.plan(listOf(item("brief.pdf"), item("draft.pdf")))
            .map { if (it.title == "draft") it.copy(include = false) else it }
        assertEquals(1, Transfer.included(plan).size)
        assertEquals("1 file · 1.2 MB · from OneDrive", Transfer.headline(plan))
    }

    @Test
    fun `a headline totals only the sizes it was actually told`() {
        // A cloud provider very often reports no size — a Google Doc has none until it is exported.
        // The total says what is known rather than counting the unknown ones as empty.
        val plan = Transfer.plan(listOf(item("brief.pdf"), item("notes.pdf", size = null)))
        assertEquals("2 files · 1.2 MB · from OneDrive", Transfer.headline(plan))

        val unknown = Transfer.plan(listOf(item("a.pdf", size = null), item("b.pdf", size = null)))
        assertEquals("2 files · from OneDrive", Transfer.headline(unknown))
        assertEquals("Nothing selected", Transfer.headline(emptyList()))
    }

    @Test
    fun `a mixed batch says every place it came from`() {
        val plan = Transfer.plan(
            listOf(
                item("brief.pdf"),
                item("scan.pdf", uri = "content://com.android.providers.downloads.documents/document/3"),
                item("deed.pdf", uri = "content://com.google.android.apps.docs.storage/document/9")
            )
        )
        assertEquals("OneDrive, This device and Google Drive", Transfer.origins(plan))
        assertNull(Transfer.origins(Transfer.plan(listOf(item("x.pdf", uri = "not-a-uri")))))
    }

    @Test
    fun `two files that would land under one name are reported, not renamed`() {
        // Repository has no versions and does not mind two documents called the same thing — this
        // year's statement and last year's out of one folder is the normal case. What it would mind
        // is finding out afterwards.
        val plan = Transfer.plan(listOf(item("statement.pdf"), item("Statement.PDF"), item("deed.pdf")))
        assertEquals(listOf("statement"), Transfer.duplicateTitles(plan))
        assertTrue(Transfer.duplicateWarning(plan)!!.contains("“statement”"))

        val renamed = plan.map { if (it.item.displayName == "Statement.PDF") it.copy(title = "Statement 2025") else it }
        assertTrue(Transfer.duplicateTitles(renamed).isEmpty())
        assertNull(Transfer.duplicateWarning(renamed))
    }

    @Test
    fun `an unticked duplicate is not a duplicate`() {
        val plan = Transfer.plan(listOf(item("statement.pdf"), item("statement.pdf")))
            .mapIndexed { index, choice -> if (index == 1) choice.copy(include = false) else choice }
        assertTrue(Transfer.duplicateTitles(plan).isEmpty())
    }

    @Test
    fun `what happened afterwards is said in files, and failures by name`() {
        assertEquals("4 documents filed", Transfer.outcomeLine(TransferOutcome(4)))
        assertEquals("1 document filed", Transfer.outcomeLine(TransferOutcome(1)))
        assertEquals(
            "3 documents filed · Q3 budget couldn't be read",
            Transfer.outcomeLine(TransferOutcome(3, listOf("Q3 budget")))
        )
        // Past two, naming them is a paragraph rather than an answer.
        assertEquals(
            "3 couldn't be read",
            Transfer.outcomeLine(TransferOutcome(0, listOf("a", "b", "c")))
        )
        assertEquals("Nothing was filed", Transfer.outcomeLine(TransferOutcome.NOTHING))
        assertTrue(TransferOutcome(2).everything)
    }

    @Test
    fun `going the other way says where it went`() {
        assertEquals("Saved to OneDrive · Project docs", Transfer.savedLine(1, Drive.ONEDRIVE, "Project docs"))
        assertEquals(
            "Saved 3 documents to Google Drive · Briefs",
            Transfer.savedLine(3, Drive.GOOGLE_DRIVE, "Briefs")
        )
        // A folder the provider would not name is still a folder somebody chose.
        assertEquals("Saved to the folder you chose", Transfer.savedLine(1, null, null))
        assertEquals("Nothing was saved", Transfer.savedLine(0, Drive.ONEDRIVE, "Project docs"))
    }
}
