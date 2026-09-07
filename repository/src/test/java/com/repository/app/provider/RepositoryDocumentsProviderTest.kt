package com.repository.app.provider

import android.Manifest.permission.MANAGE_DOCUMENTS
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.core.app.ApplicationProvider
import com.repository.app.RepositoryApp
import com.repository.app.data.repository.FakePicker
import com.repository.app.data.repository.FakeSource
import com.repository.app.data.repository.documentsDir
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.source.DocumentSources
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.RobolectricTestRunner

/**
 * The shelf as Android sees it — queried through the real `ContentResolver`, with real
 * `DocumentsContract` URIs, exactly as the system Files app would.
 *
 * The provider is a thin adapter over things already tested (`Shelf` orders and searches,
 * `RepositoryLinks` writes the ids, `DocumentRepository` holds the rows and the files), so what is
 * worth asserting here is the adaptation itself, and above all **what it refuses**: every flag it
 * publishes is zero for create, delete, write and rename, and a document opened for writing is
 * refused rather than quietly downgraded. A picker that could delete would let any app on the phone
 * destroy the only copy of a document through a dialog nobody read carefully.
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryDocumentsProviderTest {

    private lateinit var context: Context
    private lateinit var controller: ContentProviderController<RepositoryDocumentsProvider>
    private val provider get() = controller.get()

    private val march = 1_700_000_000_000L
    private val truck = DocumentOwner("maintenance", "a3f2", "2018 Jeep Wrangler")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FakePicker.install()
        DocumentSources.clear()
        // Nothing carried over from the test before: the container is a process-wide singleton, and
        // the shelf it holds is a real database in a directory this method is about to fill.
        RepositoryApp.reset()

        // A full ProviderInfo rather than `setupContentProvider(class, authority)`, because
        // `DocumentsProvider.attachInfo` refuses to start unless it is exported, grants URI
        // permissions and is protected by MANAGE_DOCUMENTS. That check is the framework asserting
        // the manifest entry this module actually ships, so satisfying it here is the point rather
        // than a hoop: a provider that would be rejected on a device is rejected in this test too.
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = MANAGE_DOCUMENTS
            writePermission = MANAGE_DOCUMENTS
        }
        controller = Robolectric.buildContentProvider(RepositoryDocumentsProvider::class.java).create(info)
    }

    @After
    fun tearDown() {
        DocumentSources.clear()
        context.documentsDir().deleteRecursively()
        RepositoryApp.reset()
    }

    // ------------------------------------------------------------------ the root

    @Test
    fun `the shelf offers itself as one root, searchable and local`() {
        val row = query(DocumentsContract.buildRootsUri(AUTHORITY)).single()

        assertEquals("shelf", row[Root.COLUMN_ROOT_ID])
        assertEquals("shelf", row[Root.COLUMN_DOCUMENT_ID])
        assertEquals("Repository", row[Root.COLUMN_TITLE])

        val flags = (row[Root.COLUMN_FLAGS] as Number).toInt()
        assertTrue("Search is the whole app — there are no folders to have filed things in", flags and Root.FLAG_SUPPORTS_SEARCH != 0)
        assertTrue(flags and Root.FLAG_LOCAL_ONLY != 0)
        assertEquals("Nothing is filed through a picker", 0, flags and Root.FLAG_SUPPORTS_CREATE)
    }

    // ------------------------------------------------------------------ browsing

    @Test
    fun `the root is the drawers, the household's own leading`() {
        file("Manual", owner = truck)
        file("Passport")

        val drawers = children("shelf")

        assertEquals(
            listOf("The household", "Maintenance"),
            drawers.map { it[Document.COLUMN_DISPLAY_NAME] }
        )
        assertEquals(
            listOf("drawer/household", "drawer/maintenance"),
            drawers.map { it[Document.COLUMN_DOCUMENT_ID] }
        )
        drawers.forEach { assertEquals(Document.MIME_TYPE_DIR, it[Document.COLUMN_MIME_TYPE]) }
    }

    @Test
    fun `a drawer holds its documents, newest first, named the way they leave by every other road`() {
        file("Manual", owner = truck, addedAt = march)
        file("Mortgage statement March 2026", owner = truck, addedAt = march + 1_000)
        file("Passport")

        val documents = children("drawer/maintenance")

        assertEquals(
            listOf("Mortgage statement March 2026.pdf", "Manual.pdf"),
            documents.map { it[Document.COLUMN_DISPLAY_NAME] }
        )
        // What a folder of scans could never say. This is the whole distance the app exists to cover.
        assertEquals(
            "2018 Jeep Wrangler · Other",
            documents.first()[Document.COLUMN_SUMMARY]
        )
        assertEquals("application/pdf", documents.first()[Document.COLUMN_MIME_TYPE])
        assertEquals(march + 1_000, (documents.first()[Document.COLUMN_LAST_MODIFIED] as Number).toLong())
    }

    @Test
    fun `a document's id is the same address that opens the shelf at it`() {
        val id = file("Manual", owner = truck)

        // One vocabulary for "somewhere on the shelf", so the id the Files app remembers in a
        // recent-documents list is the string that opens the app at that document.
        assertEquals("document/$id", children("drawer/maintenance").single()[Document.COLUMN_DOCUMENT_ID])
    }

    @Test
    fun `a lent document is on the shelf here too, addressed by its lender`() {
        DocumentSources.register(
            FakeSource(
                appKey = "health",
                documents = listOf(FakeSource.document("lab-1", "Blood panel", addedAt = march))
            )
        )

        val household = children("drawer/household").single()

        assertEquals("lent/health/lab-1", household[Document.COLUMN_DOCUMENT_ID])
        assertEquals("Blood panel.pdf", household[Document.COLUMN_DISPLAY_NAME])
    }

    @Test
    fun `searching from the Files app runs the shelf's own search`() {
        file("Manual", owner = truck)
        file("Passport")

        val found = query(DocumentsContract.buildSearchDocumentsUri(AUTHORITY, "shelf", "wrangler"))

        // The owner's label is part of the haystack, which is how a word this module does not
        // understand finds the truck's manual.
        assertEquals(listOf("Manual.pdf"), found.map { it[Document.COLUMN_DISPLAY_NAME] })
    }

    // ------------------------------------------------------------------ what it refuses

    @Test
    fun `nothing offered here can be created, deleted, written or renamed`() {
        file("Manual", owner = truck)

        val forbidden = Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_WRITE or
            Document.FLAG_SUPPORTS_RENAME or
            Document.FLAG_SUPPORTS_MOVE or
            Document.FLAG_SUPPORTS_REMOVE or
            Document.FLAG_DIR_SUPPORTS_CREATE

        (children("shelf") + children("drawer/maintenance") + listOf(document("drawer/maintenance")))
            .forEach { row ->
                val flags = (row[Document.COLUMN_FLAGS] as Number).toInt()
                assertEquals(
                    "${row[Document.COLUMN_DOCUMENT_ID]} offered a way to change the shelf",
                    0,
                    flags and forbidden
                )
            }
    }

    @Test
    fun `a document opened for reading is the file itself, and one opened for writing is refused`() {
        val id = file("Manual", owner = truck, bytes = "the manual")

        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, "document/$id")
        context.contentResolver.openInputStream(uri).use {
            assertEquals("the manual", String(it!!.readBytes()))
        }

        // Refused rather than quietly downgraded: an app told it holds a writable descriptor will
        // act as though its edits were kept.
        val refused = runCatching { provider.openDocument("document/$id", "w", null) }
        assertTrue(refused.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun `an address for something that is not on the shelf answers with nothing`() {
        file("Manual", owner = truck)

        // The provider throws `FileNotFoundException`, which the framework turns into an empty
        // answer — a file browser is shown nothing rather than an error, which is the right shape
        // for a link that has gone stale in somebody's recent-documents list.
        assertTrue(query(DocumentsContract.buildDocumentUri(AUTHORITY, "document/gone")).isEmpty())
        // A record is a real address in the app and not a place in a file browser: browsing shows
        // drawers, and a folder per asset would be the folders this app refuses to have.
        assertTrue(query(DocumentsContract.buildDocumentUri(AUTHORITY, "drawer/maintenance/a3f2")).isEmpty())
        assertTrue(query(DocumentsContract.buildDocumentUri(AUTHORITY, "nonsense")).isEmpty())
        assertTrue(query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, "document/gone")).isEmpty())
    }

    @Test
    fun `a drawer owns its own documents and nobody else's`() {
        val manual = file("Manual", owner = truck)
        val passport = file("Passport")
        assertTrue(provider.isChildDocument("drawer/maintenance", "document/$manual"))
        assertFalse(provider.isChildDocument("drawer/maintenance", "document/$passport"))
        assertTrue(provider.isChildDocument("drawer/household", "document/$passport"))
        assertTrue("Everything is under the shelf", provider.isChildDocument("shelf", "drawer/maintenance"))
    }

    // ------------------------------------------------------------------ helpers

    private fun file(
        title: String,
        owner: DocumentOwner = DocumentOwner.HOUSEHOLD,
        addedAt: Long = march,
        bytes: String = "a document"
    ): String = runBlocking {
        RepositoryApp.get(context).documents.file(
            source = FakePicker.offer("${title.lowercase().replace(' ', '-')}.pdf", bytes = bytes.toByteArray()),
            title = title,
            kind = DocumentKind.OTHER,
            owner = owner,
            addedAt = addedAt
        )!!
    }

    private fun children(documentId: String) =
        query(DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentId))

    private fun document(documentId: String) =
        query(DocumentsContract.buildDocumentUri(AUTHORITY, documentId)).single()

    private companion object {
        const val AUTHORITY = "com.repository.app.test.documents"
    }

    /**
     * A query as the document UI makes one — through the real resolver, and through the *Bundle*
     * form, because `DocumentsProvider` refuses the older selection/sortOrder shape outright.
     */
    private fun query(uri: Uri): List<Map<String, Any?>> {
        val cursor = context.contentResolver.query(uri, null, null as Bundle?, null)
            ?: return emptyList()
        return cursor.use { it.rows() }
    }

    private fun Cursor.rows(): List<Map<String, Any?>> = buildList {
        while (moveToNext()) {
            add(
                (0 until columnCount).associate { index ->
                    getColumnName(index) to when (getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                        Cursor.FIELD_TYPE_STRING -> getString(index)
                        else -> getString(index)
                    }
                }
            )
        }
    }
}
