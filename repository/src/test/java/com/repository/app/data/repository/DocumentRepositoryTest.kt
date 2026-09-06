package com.repository.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.repository.app.data.db.RepositoryDatabase
import com.repository.app.data.store.DocumentFiles
import com.repository.app.logic.DocumentFacts
import com.repository.app.logic.DocumentKind
import com.repository.app.logic.DocumentOwner
import com.repository.app.logic.TransferChoice
import com.repository.app.logic.TransferItem
import com.repository.app.source.DocumentSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The store's own rules, against a real database and a real folder of files.
 *
 * Everything above this layer is pure and is tested by reasoning about it — the ordering, the
 * search, the drawers, the export names all live in `logic/` and have their own tests. What is left
 * over is the half only SQLite and a file system can be asked about, and in this module that half is
 * unusually load-bearing: **the rows here are captions**. Every other app in the suite could, at
 * worst, be typed in again; a row that outlives its file is a document the household believes it has
 * and cannot open, and a file that outlives its row is a mortgage statement nothing will ever delete.
 *
 * So what is asserted below is mostly about those two staying together:
 *
 * - that a file which **cannot be read leaves nothing behind** — no row, and no half-written file;
 * - that **deleting takes the bytes with the row**, through the single delete and through the
 *   cascade an owning app calls when one of its records goes;
 * - that a cascade is keyed on **both** the app and the record, because two apps number their
 *   records from one and a shelf that ignored the app would empty the wrong drawer;
 * - that a document another app is only **lending cannot be written to** from here, however it is
 *   addressed — the shelf shows it, and Health still owns it;
 * - that a **drawer appears when its app registers**, without the shelf being closed and reopened;
 * - that a document **leaves by one road** whether the shelf holds it or a lender does.
 *
 * A fake DAO would answer every one of those with whatever this file assumed. The database below is
 * in memory, but it is a real Room database — the same entities, the same generated SQL — and the
 * files are really written, really copied and really deleted.
 */
@RunWith(RobolectricTestRunner::class)
class DocumentRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: RepositoryDatabase
    private lateinit var files: DocumentFiles
    private lateinit var repo: DocumentRepository

    /** A fixed past, so "filed later" is a fact of the fixture rather than of how fast a test runs. */
    private val march = 1_700_000_000_000L

    private val truck = DocumentOwner("maintenance", "a3f2", "2018 Jeep Wrangler")
    private val house = DocumentOwner("maintenance", "b7c1", "12 Oak Lane")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FakePicker.install()
        // Static, because the shelf must not know which apps exist; cleared so no test inherits a
        // drawer from the one before it.
        DocumentSources.clear()
        db = Room.inMemoryDatabaseBuilder(context, RepositoryDatabase::class.java).build()
        files = DocumentFiles(context)
        repo = DocumentRepository(db.repositoryDao(), files)
    }

    @After
    fun tearDown() {
        db.close()
        DocumentSources.clear()
        context.documentsDir().deleteRecursively()
    }

    // ------------------------------------------------------------------ filing

    @Test
    fun `filing writes a row and the bytes behind it`() = runTest {
        val id = repo.file(
            source = FakePicker.offer("2026-03-statement.pdf", bytes = "the statement".toByteArray()),
            title = "Mortgage statement March 2026",
            kind = DocumentKind.STATEMENT,
            owner = house,
            addedAt = march
        )

        assertNotNull(id)
        val facts = repo.get(id!!)!!
        assertEquals("Mortgage statement March 2026", facts.title)
        assertEquals(DocumentKind.STATEMENT, facts.kind)
        assertEquals(house, facts.owner)
        assertEquals("application/pdf", facts.mimeType)
        assertNull("A row this app wrote is not lent by anybody", facts.sourceKey)

        // The row names a file, and the file is there with the bytes that were picked.
        val row = repo.allRows().single()
        val stored = File(context.documentsDir(), row.fileName)
        assertTrue(stored.exists())
        assertEquals("the statement", stored.readText())
        // A bare name, never a path: the store is a flat folder and the backup restores it by name.
        assertFalse(row.fileName.contains('/'))
        assertTrue("The file the household was given keeps its own extension", row.fileName.endsWith(".pdf"))
    }

    @Test
    fun `a file that cannot be read files nothing at all`() = runTest {
        val id = repo.file(
            source = FakePicker.offerUnreadable("2026-03-statement.pdf"),
            title = "Mortgage statement March 2026",
            kind = DocumentKind.STATEMENT
        )

        assertNull("The caller is told, and its form stays open", id)
        assertTrue("A row pointing at a file that was never written is the one thing to avoid", repo.allRows().isEmpty())
        assertTrue("And a half-copied file is not left behind either", context.storedFiles().isEmpty())
    }

    @Test
    fun `the row records the bytes that arrived, not the size the picker claimed`() = runTest {
        val id = repo.file(
            // A cloud provider reporting a size it does not have is the ordinary case, not a freak one.
            source = FakePicker.offer("export.pdf", bytes = "ten bytes!".toByteArray(), claimedSize = 999_999L),
            title = "Export",
            kind = DocumentKind.OTHER
        )

        assertEquals(10L, repo.get(id!!)!!.sizeBytes)
    }

    @Test
    fun `a blank title falls back to the name of the file, not to Untitled`() = runTest {
        val id = repo.file(
            source = FakePicker.offer("2026-03-statement.pdf"),
            title = "   ",
            kind = DocumentKind.STATEMENT
        )

        // Cleaned up, never guessed at: nothing here knows March from the number 03.
        assertEquals("2026 03 statement", repo.get(id!!)!!.title)
    }

    @Test
    fun `a picker that will not say what a file is called still files something openable`() = runTest {
        // Plenty of providers supply neither column. The document must still arrive with a name a
        // person can rename, rather than the app refusing a file it can perfectly well read.
        val id = repo.file(
            source = FakePicker.offer(displayName = null, mimeType = null, claimedSize = null),
            title = "",
            kind = DocumentKind.OTHER
        )

        assertEquals("Document", repo.get(id!!)!!.title)
        assertTrue("An unknown type is honestly bin rather than a guess", repo.allRows().single().fileName.endsWith(".bin"))
    }

    @Test
    fun `a photographed document is stored as a picture and stops claiming to be a HEIC`() = runTest {
        val id = repo.file(
            source = FakePicker.offer("IMG_4021.heic", mimeType = "image/heic"),
            title = "The survey",
            kind = DocumentKind.REPORT
        )

        // A photo is re-encoded, so the row must describe what is now on disk — a share sheet reads
        // that type, and announcing a HEIC that is really a JPEG hands the next app a lie.
        assertEquals("image/jpeg", repo.get(id!!)!!.mimeType)
        assertTrue(repo.allRows().single().fileName.endsWith(".jpg"))
    }

    @Test
    fun `a note of nothing but spaces is stored as no note`() = runTest {
        val id = repo.file(
            source = FakePicker.offer("warranty.pdf"),
            title = "Warranty",
            kind = DocumentKind.WARRANTY,
            note = "   "
        )

        assertNull(repo.get(id!!)!!.note)
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun `the shelf hands back the newest first, whatever order things were filed in`() = runTest {
        fileOne("Manual", owner = truck, addedAt = march)
        fileOne("Warranty", owner = truck, addedAt = march + 2_000)
        fileOne("Receipt", owner = truck, addedAt = march + 1_000)

        assertEquals(
            listOf("Warranty", "Receipt", "Manual"),
            repo.observeOwn().first().map { it.title }
        )
    }

    @Test
    fun `a record's section shows that record's documents and no others`() = runTest {
        fileOne("Manual", owner = truck, addedAt = march)
        fileOne("Service history", owner = truck, addedAt = march + 1_000)
        fileOne("Deed", owner = house, addedAt = march + 2_000)
        fileOne("Passport", owner = DocumentOwner.HOUSEHOLD, addedAt = march + 3_000)

        assertEquals(
            listOf("Service history", "Manual"),
            repo.observeOn("maintenance", "a3f2").first().map { it.title }
        )
    }

    // ------------------------------------------------------------------ editing

    @Test
    fun `renaming a document leaves it in the drawer it was in`() = runTest {
        val id = fileOne("Manul", owner = truck, note = "cab", addedAt = march)

        repo.update(id, title = "Manual", kind = DocumentKind.MANUAL, note = null)

        val facts = repo.get(id)!!
        assertEquals("Manual", facts.title)
        assertEquals(DocumentKind.MANUAL, facts.kind)
        assertNull("A note cleared in the form is cleared on the row", facts.note)
        assertEquals("A rename is not a move — this is the whole reason refile is a separate call", truck, facts.owner)
        assertTrue(repo.allRows().single().updatedAt > march)
    }

    @Test
    fun `a rename that says nothing keeps the name the document had`() = runTest {
        val id = fileOne("Manual", owner = truck, addedAt = march)

        repo.update(id, title = "   ", kind = DocumentKind.MANUAL, note = null)

        assertEquals("Manual", repo.get(id)!!.title)
    }

    @Test
    fun `re-filing moves a document between drawers, and back out to the household's`() = runTest {
        val id = fileOne("Survey", owner = truck, addedAt = march)

        repo.refile(id, house)
        assertEquals(house, repo.get(id)!!.owner)

        // The case `update` cannot express: all three fields set, including to null.
        repo.refile(id, DocumentOwner.HOUSEHOLD)
        val facts = repo.get(id)!!
        assertEquals(DocumentOwner.HOUSEHOLD, facts.owner)
        assertFalse("The household's own drawer is a real place, not a missing value", facts.owner.isFiled)
    }

    @Test
    fun `renaming a truck renames every document on it and nothing else`() = runTest {
        val manual = fileOne("Manual", owner = truck, addedAt = march)
        val warranty = fileOne("Warranty", owner = truck, addedAt = march)
        val deed = fileOne("Deed", owner = house, addedAt = march)
        val passport = fileOne("Passport", owner = DocumentOwner.HOUSEHOLD, addedAt = march)

        repo.relabel("maintenance", "a3f2", "2018 Jeep Wrangler Rubicon")

        assertEquals("2018 Jeep Wrangler Rubicon", repo.get(manual)!!.owner.label)
        assertEquals("2018 Jeep Wrangler Rubicon", repo.get(warranty)!!.owner.label)
        assertEquals("12 Oak Lane", repo.get(deed)!!.owner.label)
        assertNull(repo.get(passport)!!.owner.label)
        assertTrue(rowFor(manual).updatedAt > march)
        assertEquals("Nothing else is even stamped", march, rowFor(deed).updatedAt)
    }

    @Test
    fun `two apps numbering their records from one keep their drawers apart`() = runTest {
        // The key is the pair, not the record: `a3f2` in Maintenance and `a3f2` in Project are two
        // different things, and a shelf that ignored the app would rename or empty the wrong one.
        val asset = fileOne("Manual", owner = DocumentOwner("maintenance", "a3f2", "Truck"), addedAt = march)
        val project = fileOne("Brief", owner = DocumentOwner("project", "a3f2", "The Kestrel"), addedAt = march)

        repo.relabel("maintenance", "a3f2", "2018 Jeep Wrangler")
        assertEquals("2018 Jeep Wrangler", repo.get(asset)!!.owner.label)
        assertEquals("The Kestrel", repo.get(project)!!.owner.label)

        repo.deleteFiledOn("maintenance", "a3f2")
        assertNull(repo.get(asset))
        assertNotNull("Project's document is still on the shelf", repo.get(project))
    }

    @Test
    fun `editing something that is not there does nothing and says nothing`() = runTest {
        // A stale id from a screen that was open while something else deleted the row. Every write
        // here reads first and returns, so the shelf cannot grow a row out of a bad id.
        repo.update("gone", title = "Manual", kind = DocumentKind.MANUAL, note = null)
        repo.refile("gone", house)
        repo.delete("gone")

        assertTrue(repo.allRows().isEmpty())
    }

    // ------------------------------------------------------------------ deleting

    @Test
    fun `deleting a document takes the bytes with the row`() = runTest {
        val id = fileOne("Statement", owner = house, addedAt = march)
        val stored = File(context.documentsDir(), rowFor(id).fileName)
        assertTrue(stored.exists())

        repo.delete(id)

        assertNull(repo.get(id))
        assertFalse("A file nothing points at is a mortgage statement nothing will ever delete", stored.exists())
    }

    @Test
    fun `an app deleting one of its records clears that drawer and leaves the shelf standing`() = runTest {
        val manual = fileOne("Manual", owner = truck, addedAt = march)
        val warranty = fileOne("Warranty", owner = truck, addedAt = march)
        val deed = fileOne("Deed", owner = house, addedAt = march)
        val passport = fileOne("Passport", owner = DocumentOwner.HOUSEHOLD, addedAt = march)
        val truckFiles = listOf(manual, warranty).map { rowFor(it).fileName }

        repo.deleteFiledOn("maintenance", "a3f2")

        assertEquals(listOf("Deed", "Passport").sorted(), repo.allRows().map { it.title }.sorted())
        assertNotNull(repo.get(deed))
        assertNotNull(repo.get(passport))
        truckFiles.forEach { name ->
            assertFalse("The cascade is over files too, not just rows", File(context.documentsDir(), name).exists())
        }
        assertEquals("And it took exactly two", 2, context.storedFiles().size)
    }

    // ------------------------------------------------------------------ what a lender lends

    @Test
    fun `the shelf is this app's documents and every lender's, in one list`() = runTest {
        fileOne("Deed", owner = house, addedAt = march + 1_000)
        DocumentSources.register(
            FakeSource(
                appKey = "health",
                documents = listOf(FakeSource.document("lab-1", "Blood panel", addedAt = march + 2_000))
            )
        )

        val shelf = repo.observeShelf().first()

        assertEquals(listOf("Blood panel", "Deed"), shelf.map { it.title })
        val lent = shelf.first()
        assertEquals("Stamped by the shelf, so nothing downstream has to remember", "health", lent.sourceKey)
        assertTrue(lent.isForeign)
        assertFalse(shelf.last().isForeign)
    }

    @Test
    fun `a document a lender holds cannot be written to from here`() = runTest {
        val source = FakeSource(
            appKey = "health",
            documents = listOf(FakeSource.document("lab-1", "Blood panel", addedAt = march))
        )
        DocumentSources.register(source)

        // However it is addressed. Health deletes a person's documents with the person, and a second
        // writer would either duplicate that rule or break it.
        repo.update("lab-1", title = "Renamed", kind = DocumentKind.OTHER, note = "mine now")
        repo.refile("lab-1", truck)
        repo.delete("lab-1")

        assertTrue("Nothing was written into this app's store", repo.allRows().isEmpty())
        val shelf = repo.observeShelf().first()
        assertEquals(listOf("Blood panel"), shelf.map { it.title })
        assertEquals(DocumentOwner.HOUSEHOLD, shelf.single().owner)
    }

    @Test
    fun `a drawer appears when its app registers, without the shelf being reopened`() = runBlocking {
        // The one test here that has to be a live collection rather than a first(): the claim is
        // about re-subscription — an app registers while somebody is *looking* at the shelf — and
        // asking the flow twice would prove only that a new collector sees the new drawer.
        fileOne("Deed", owner = house, addedAt = march)
        val seen = Channel<List<DocumentFacts>>(Channel.UNLIMITED)
        val collector = CoroutineScope(Dispatchers.Default).launch {
            repo.observeShelf().collect { seen.send(it) }
        }

        try {
            assertEquals(listOf("Deed"), withTimeout(TIMEOUT_MS) { seen.receive() }.map { it.title })

            DocumentSources.register(
                FakeSource(
                    appKey = "health",
                    documents = listOf(FakeSource.document("lab-1", "Blood panel", addedAt = march + 1_000))
                )
            )

            val withDrawer = withTimeout(TIMEOUT_MS) {
                var latest = seen.receive()
                while (latest.size < 2) latest = seen.receive()
                latest
            }
            assertEquals(listOf("Blood panel", "Deed"), withDrawer.map { it.title })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a lender that cannot answer costs a drawer, never the shelf`() = runTest {
        fileOne("Deed", owner = house, addedAt = march)
        DocumentSources.register(BrokenSource("health"))

        assertEquals(listOf("Deed"), repo.observeShelf().first().map { it.title })
    }

    // ------------------------------------------------------------------ handing one over

    @Test
    fun `a copy leaves under the document's title, not the name it is stored as`() = runTest {
        val id = fileOne("Mortgage statement March 2026", owner = house, addedAt = march, bytes = "the statement")

        val copy = repo.exportCopy(repo.get(id)!!)!!

        assertEquals("Mortgage statement March 2026.pdf", copy.name)
        assertEquals("the statement", copy.readText())
        assertEquals(
            "Documents leave by exactly one road, and it is the only one the file provider exposes",
            File(context.cacheDir, "exports").absolutePath,
            copy.parentFile!!.absolutePath
        )
    }

    @Test
    fun `a lent document leaves by the same road as one the shelf holds`() = runTest {
        val held = File(context.cacheDir, "health-lab-1.pdf").apply { writeText("the panel") }
        DocumentSources.register(
            FakeSource(
                appKey = "health",
                documents = listOf(FakeSource.document("lab-1", "Blood panel", addedAt = march)),
                stored = held
            )
        )
        val lent = repo.observeShelf().first().single()

        val copy = repo.exportCopy(lent)!!

        assertEquals("Blood panel.pdf", copy.name)
        assertEquals("the panel", copy.readText())
        assertTrue("The lending app's own file is not moved or written to", held.exists())
    }

    @Test
    fun `sending a document whose lender has gone is nothing, not a crash`() = runTest {
        val source = FakeSource(
            appKey = "health",
            documents = listOf(FakeSource.document("lab-1", "Blood panel", addedAt = march)),
            stored = File(context.cacheDir, "health-lab-1.pdf").apply { writeText("the panel") }
        )
        DocumentSources.register(source)
        val lent = repo.observeShelf().first().single()

        // Health is uninstalled, or has not started yet, while the shelf still shows what it lent.
        DocumentSources.unregister("health")

        assertNull(repo.exportCopy(lent))
    }

    // ------------------------------------------------------------------ a reviewed batch

    @Test
    fun `a batch files what it can and names what it could not`() = runTest {
        val choices = listOf(
            choice(FakePicker.offer("brief.pdf").toString(), "The brief"),
            choice(FakePicker.offerUnreadable("notes.gdoc").toString(), "Notes"),
            choice(FakePicker.offer("contract.pdf").toString(), "The contract"),
            choice(FakePicker.offer("draft.pdf").toString(), "An old draft", include = false)
        )

        val outcome = repo.fileAll(choices, kind = DocumentKind.CONTRACT, owner = house)

        // Two of three attempted. Three of four documents filed is three the household has; an
        // all-or-nothing import would throw away two good copies over one Google Doc.
        assertEquals(2, outcome.filed)
        assertEquals("Named, because 'one of these failed' makes somebody re-import all four", listOf("Notes"), outcome.failed)
        assertFalse(outcome.everything)
        assertEquals(
            listOf("The brief", "The contract"),
            repo.observeOwn().first().map { it.title }.sorted()
        )
        assertEquals("The one that was unticked was not filed anyway", 2, context.storedFiles().size)
    }

    @Test
    fun `a batch that reads everything says so`() = runTest {
        val choices = listOf(
            choice(FakePicker.offer("brief.pdf").toString(), "The brief"),
            choice(FakePicker.offer("contract.pdf").toString(), "The contract")
        )

        val outcome = repo.fileAll(choices, kind = DocumentKind.CONTRACT)

        assertEquals(2, outcome.filed)
        assertTrue(outcome.everything)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun fileOne(
        title: String,
        owner: DocumentOwner = DocumentOwner.HOUSEHOLD,
        addedAt: Long = march,
        note: String? = null,
        bytes: String = "a document"
    ): String = repo.file(
        source = FakePicker.offer("${title.lowercase().replace(' ', '-')}.pdf", bytes = bytes.toByteArray()),
        title = title,
        kind = DocumentKind.OTHER,
        owner = owner,
        note = note,
        addedAt = addedAt
    )!!

    private suspend fun rowFor(id: String) = repo.allRows().single { it.id == id }

    private fun choice(uri: String, title: String, include: Boolean = true) = TransferChoice(
        item = TransferItem(uri = uri, displayName = title, mimeType = "application/pdf", sizeBytes = 10L),
        title = title,
        include = include
    )

    private companion object {
        /** Generous: this is a guard against a hang, not a measurement of anything. */
        const val TIMEOUT_MS = 10_000L
    }
}
