package com.repository.app.connection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.operations.connectkit.ConnectionError
import com.operations.connectkit.ConnectionParams
import com.operations.connectkit.ConnectionResult
import com.repository.app.data.db.RepositoryDatabase
import com.repository.app.data.repository.DocumentRepository
import com.repository.app.data.repository.FakePicker
import com.repository.app.data.repository.FakeSource
import com.repository.app.data.repository.documentsDir
import com.repository.app.data.repository.storedFiles
import com.repository.app.data.store.DocumentFiles
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.source.DocumentSources
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Repository's routes, and — more importantly — the ones it does not have.
 *
 * The shelf is the app most obviously worth addressing in a sentence and the app where a route can
 * do the most damage, so half of this file is about the second thing. **Filing is not routable**
 * (a picked document is a `Uri` and a permission grant, not a payload), **nothing deletes** (the
 * bytes may be the only copy of that document in the house), and **nothing exports** (a document
 * leaves this device by one road, opened by the household pressing Send and not by a caller). Each
 * of those addresses is asserted `ROUTE_NOT_FOUND` below, which is what makes the line in
 * `RepositoryConnections`' doc a fact rather than an intention: adding any of them means deleting a
 * test that says why not.
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryConnectionsTest {

    private lateinit var context: Context
    private lateinit var db: RepositoryDatabase
    private lateinit var documents: DocumentRepository
    private val dispatcher by lazy { RepositoryConnections.buildDispatcher(documents) }

    private val march = 1_700_000_000_000L
    private val truck = DocumentOwner("maintenance", "a3f2", "2018 Jeep Wrangler")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FakePicker.install()
        DocumentSources.clear()
        db = Room.inMemoryDatabaseBuilder(context, RepositoryDatabase::class.java).build()
        documents = DocumentRepository(db.repositoryDao(), DocumentFiles(context))
    }

    @After
    fun tearDown() {
        db.close()
        DocumentSources.clear()
        context.documentsDir().deleteRecursively()
    }

    // ------------------------------------------------------------------ the line

    @Test
    fun `nothing here can put a document on the shelf, take one off it, or hand one out`() = runTest {
        file("Mortgage statement", owner = truck)

        listOf(
            // Filing: bound to a picked Uri and a permission grant, not to anything a sentence can
            // carry. The same exclusion LifeOps makes for task image attachments.
            "/v1/Repository/local/document/file",
            "/v1/Repository/local/document/create",
            "/v1/Repository/local/document/add",
            // Deleting: undone by nothing. A row is recoverable by typing it again; the scan of the
            // title is not.
            "/v1/Repository/local/document/delete",
            "/v1/Repository/local/document/remove",
            "/v1/Repository/local/drawer/delete",
            // Handing one out: a second road off the device, opened by a caller rather than by the
            // household pressing Send.
            "/v1/Repository/local/document/export",
            "/v1/Repository/local/document/send",
            "/v1/Repository/local/document/open"
        ).forEach { address ->
            val result = dispatcher.dispatch(address, ConnectionParams.of("document" to "Mortgage statement"))
            assertEquals(
                "$address should not exist",
                ConnectionError.ROUTE_NOT_FOUND,
                (result as ConnectionResult.Failure).error
            )
        }

        // And the document is still there, unchanged, after being asked for in nine ways.
        assertEquals(1, documents.everything().size)
        assertEquals(1, context.storedFiles().size)
    }

    @Test
    fun `this dispatcher answers for Repository and nothing else`() = runTest {
        val result = dispatcher.dispatch("/v1/LifeOps/local/task/create")

        assertEquals(
            ConnectionError.UNKNOWN_APPLICATION,
            (result as ConnectionResult.Failure).error
        )
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun `list hands back the shelf, newest first, with what each document is about`() = runTest {
        file("Manual", owner = truck, addedAt = march)
        file("Warranty", owner = truck, addedAt = march + 1_000)
        file("Passport", addedAt = march + 2_000)

        val result = dispatcher.dispatch("/v1/Repository/local/document/list").success()

        assertEquals(3, result["count"])
        val listed = result.documents()
        assertEquals(listOf("Passport", "Warranty", "Manual"), listed.map { it["title"] })
        // Carried, not looked up: this module cannot ask Maintenance what `a3f2` is.
        assertEquals("2018 Jeep Wrangler", listed[1]["about"])
        assertEquals("maintenance", listed[1]["app"])
        assertNull("A document filed against nothing is the household's own", listed[0]["app"])
    }

    @Test
    fun `list narrows to a drawer, or to one record, or to the household's own`() = runTest {
        file("Manual", owner = truck)
        file("Deed", owner = DocumentOwner("maintenance", "b7c1", "12 Oak Lane"))
        file("Passport")

        assertEquals(
            listOf("Deed", "Manual").sorted(),
            dispatcher.dispatch("/v1/Repository/local/document/list", ConnectionParams.of("app" to "maintenance"))
                .success().documents().map { it["title"] as String }.sorted()
        )
        assertEquals(
            listOf("Manual"),
            dispatcher.dispatch(
                "/v1/Repository/local/document/list",
                ConnectionParams.of("app" to "maintenance", "record" to "a3f2")
            ).success().documents().map { it["title"] }
        )
        assertEquals(
            listOf("Passport"),
            dispatcher.dispatch("/v1/Repository/local/document/list", ConnectionParams.of("household" to true))
                .success().documents().map { it["title"] }
        )
    }

    @Test
    fun `search finds the truck's manual by a word this module does not understand`() = runTest {
        file("Manual", owner = truck)
        file("Passport")

        val found = dispatcher.dispatch(
            "/v1/Repository/local/document/search",
            ConnectionParams.of("query" to "wrangler")
        ).success().documents()

        assertEquals(listOf("Manual"), found.map { it["title"] })
    }

    @Test
    fun `the drawers say what the household has paperwork about`() = runTest {
        file("Manual", owner = truck)
        file("Passport")
        DocumentSources.register(
            FakeSource(
                appKey = "health",
                documents = listOf(FakeSource.document("lab-1", "Blood panel", addedAt = march))
            )
        )

        @Suppress("UNCHECKED_CAST")
        val drawers = dispatcher.dispatch("/v1/Repository/local/drawer/list").success()["drawers"]
            as List<Map<String, Any?>>

        // The household's own leads, as it does on screen: a document belonging to no app is the one
        // nothing else will ever show you. A lender's documents are drawers like any other.
        assertNull(drawers.first()["app"])
        assertEquals("The household", drawers.first()["label"])
        assertEquals(setOf(null, "maintenance"), drawers.map { it["app"] }.toSet())
        assertEquals(2, drawers.first()["count"])
    }

    // ------------------------------------------------------------------ naming the target

    @Test
    fun `a document is named the way a sentence names it, or by id`() = runTest {
        val id = file("Mortgage statement March 2026", owner = truck)

        assertEquals(
            id,
            dispatcher.dispatch(
                "/v1/Repository/local/document/get",
                ConnectionParams.of("document" to "mortgage statement march 2026")
            ).success().document()["id"]
        )
        assertEquals(
            id,
            dispatcher.dispatch("/v1/Repository/local/document/get", ConnectionParams.of("document" to id))
                .success().document()["id"]
        )
    }

    @Test
    fun `two documents of the same name resolve to neither, and both are named back`() = runTest {
        file("Statement", owner = truck)
        file("Statement", owner = DocumentOwner("maintenance", "b7c1", "12 Oak Lane"))

        val result = dispatcher.dispatch(
            "/v1/Repository/local/document/get",
            ConnectionParams.of("document" to "Statement")
        ) as ConnectionResult.Failure

        // Not NOT_FOUND: it *was* found, twice. The caller has to say something else, and is told
        // enough to. Picking one would rename or detach a document nobody asked about.
        assertEquals(ConnectionError.INVALID_PARAMS, result.error)
        assertTrue(result.message.contains("More than one document is called 'Statement'"))
    }

    @Test
    fun `a name nothing answers to is not found`() = runTest {
        file("Manual", owner = truck)

        val result = dispatcher.dispatch(
            "/v1/Repository/local/document/get",
            ConnectionParams.of("document" to "The deed")
        ) as ConnectionResult.Failure

        assertEquals(ConnectionError.NOT_FOUND, result.error)
    }

    @Test
    fun `a route that needs a target and is not given one says which parameter is missing`() = runTest {
        val result = dispatcher.dispatch("/v1/Repository/local/document/get") as ConnectionResult.Failure

        assertEquals(ConnectionError.INVALID_PARAMS, result.error)
        assertTrue(result.message.contains("document"))
    }

    // ------------------------------------------------------------------ correcting a caption

    @Test
    fun `a mistyped title is fixed without touching anything else`() = runTest {
        val id = file("Mortage statement", owner = truck, note = "the one from the bank")

        dispatcher.dispatch(
            "/v1/Repository/local/document/update",
            ConnectionParams.of("document" to "Mortage statement", "title" to "Mortgage statement")
        ).success()

        val document = documents.get(id)!!
        assertEquals("Mortgage statement", document.title)
        assertEquals("An omitted field is left alone", "the one from the bank", document.note)
        assertEquals(DocumentKind.OTHER, document.kind)
        assertEquals("And the drawer it is in is not a caption", truck, document.owner)
        assertEquals("Least of all the file", 1, context.storedFiles().size)
    }

    @Test
    fun `a note sent blank is cleared, and one not sent at all is kept`() = runTest {
        val id = file("Manual", owner = truck, note = "cab, not engine")

        dispatcher.dispatch(
            "/v1/Repository/local/document/update",
            ConnectionParams.of("document" to id, "kind" to "manual")
        ).success()
        assertEquals("cab, not engine", documents.get(id)!!.note)
        assertEquals(DocumentKind.MANUAL, documents.get(id)!!.kind)

        dispatcher.dispatch(
            "/v1/Repository/local/document/update",
            ConnectionParams.of("document" to id, "note" to "  ")
        ).success()
        assertNull(documents.get(id)!!.note)
    }

    @Test
    fun `a kind nobody recognises is refused rather than filed under Other`() = runTest {
        val id = file("Manual", owner = truck)

        val result = dispatcher.dispatch(
            "/v1/Repository/local/document/update",
            ConnectionParams.of("document" to id, "kind" to "blueprint")
        ) as ConnectionResult.Failure

        // Reading it as "Other" would lose what the caller said and report success for it.
        assertEquals(ConnectionError.INVALID_PARAMS, result.error)
        assertTrue(result.message.contains("manual"))
        assertEquals(DocumentKind.OTHER, documents.get(id)!!.kind)
    }

    @Test
    fun `detaching puts a document back in the household's drawer and leaves it on the shelf`() = runTest {
        val id = file("Survey", owner = truck)

        dispatcher.dispatch(
            "/v1/Repository/local/document/detach",
            ConnectionParams.of("document" to "Survey")
        ).success()

        val document = documents.get(id)!!
        assertEquals(DocumentOwner.HOUSEHOLD, document.owner)
        assertNotNull("Detaching is not deleting — it is still there and still openable", documents.get(id))
        assertEquals(1, context.storedFiles().size)
    }

    // ------------------------------------------------------------------ what a lender lends

    @Test
    fun `a lent document can be found through a route and not written to`() = runTest {
        DocumentSources.register(
            FakeSource(
                appKey = "health",
                documents = listOf(FakeSource.document("lab-1", "Blood panel", addedAt = march))
            )
        )

        // Findable, because a household asking where the lab result is does not care which app is
        // holding it, and answering "no such document" about one in plain view is a worse answer.
        val found = dispatcher.dispatch(
            "/v1/Repository/local/document/get",
            ConnectionParams.of("document" to "Blood panel")
        ).success().document()
        assertEquals("health", found["lentBy"])

        // Not writable, and *said* rather than silently ignored: a success that changed nothing is
        // the failure a caller cannot see.
        listOf("update", "detach").forEach { action ->
            val result = dispatcher.dispatch(
                "/v1/Repository/local/document/$action",
                ConnectionParams.of("document" to "Blood panel", "title" to "Mine now")
            ) as ConnectionResult.Failure
            assertEquals(ConnectionError.INVALID_PARAMS, result.error)
            assertTrue(result.message.contains("lent to the shelf by health"))
        }

        assertTrue("And nothing was written into this app's store", documents.allRows().isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun file(
        title: String,
        owner: DocumentOwner = DocumentOwner.HOUSEHOLD,
        note: String? = null,
        addedAt: Long = march
    ): String = documents.file(
        source = FakePicker.offer("${title.lowercase().replace(' ', '-')}.pdf"),
        title = title,
        kind = DocumentKind.OTHER,
        owner = owner,
        note = note,
        addedAt = addedAt
    )!!

    private fun ConnectionResult.success(): ConnectionResult.Success {
        if (this is ConnectionResult.Failure) throw AssertionError("Expected success, got $error: $message")
        return this as ConnectionResult.Success
    }

    @Suppress("UNCHECKED_CAST")
    private fun ConnectionResult.Success.documents(): List<Map<String, Any?>> =
        this["documents"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun ConnectionResult.Success.document(): Map<String, Any?> =
        this["document"] as Map<String, Any?>
}
