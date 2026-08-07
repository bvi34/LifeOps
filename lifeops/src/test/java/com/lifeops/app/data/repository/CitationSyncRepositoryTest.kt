package com.lifeops.app.data.repository

import com.citation.core.key.EntityKey
import com.citation.core.key.EntityType
import com.citation.core.model.SourceType
import com.citation.core.note.Note
import com.citation.core.note.NoteType
import com.citation.core.note.SourceDescriptor
import com.citation.core.sync.FileEnvelopeStore
import com.citation.core.sync.Mailbox
import com.citation.core.sync.NotePacket
import com.citation.core.sync.OutboundEnvelope
import com.citation.core.sync.TelemetryPacket
import com.citation.core.sync.UpPacket
import com.lifeops.app.data.db.dao.BookDao
import com.lifeops.app.data.db.entities.BookEntity
import com.lifeops.app.data.db.entities.BookNoteEntity
import com.lifeops.app.data.db.entities.BookTimeEntryEntity
import com.lifeops.app.util.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CitationSyncRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Minimal in-memory BookDao: enough for the ingestion paths (getById / upsert / insertNote /
    // insertTimeEntry). REPLACE-on-conflict semantics mirror the Room annotations.
    private class FakeBookDao : BookDao {
        val books = mutableListOf<BookEntity>()
        val notes = mutableListOf<BookNoteEntity>()
        val times = mutableListOf<BookTimeEntryEntity>()

        override fun observeAll(): Flow<List<BookEntity>> = flowOf(books.toList())
        override suspend fun getAll(): List<BookEntity> = books.toList()
        override suspend fun getAllNotes(): List<BookNoteEntity> = notes.toList()
        override suspend fun getAllTimeEntries(): List<BookTimeEntryEntity> = times.toList()
        override fun observeById(id: String): Flow<BookEntity?> = flowOf(books.firstOrNull { it.id == id })
        override suspend fun getById(id: String): BookEntity? = books.firstOrNull { it.id == id }
        override suspend fun upsert(book: BookEntity) { books.removeAll { it.id == book.id }; books += book }
        override suspend fun delete(id: String) { books.removeAll { it.id == id } }
        override fun observeNotes(bookId: String): Flow<List<BookNoteEntity>> =
            flowOf(notes.filter { it.bookId == bookId })
        override suspend fun insertNote(note: BookNoteEntity) { notes.removeAll { it.id == note.id }; notes += note }
        override suspend fun deleteNote(id: String) { notes.removeAll { it.id == id } }
        override fun observeTimeEntries(bookId: String): Flow<List<BookTimeEntryEntity>> =
            flowOf(times.filter { it.bookId == bookId })
        override suspend fun insertTimeEntry(entry: BookTimeEntryEntity) { times.removeAll { it.id == entry.id }; times += entry }
        override suspend fun deleteTimeEntry(id: String) { times.removeAll { it.id == id } }
        override suspend fun sumReadingMinutesBetween(startDate: String, endDate: String): Int =
            times.filter { it.recordedAt.substring(0, 10) in startDate..endDate }.sumOf { it.durationMinutes }
    }

    private fun bookKey(seq: Long) = EntityKey(EntityKey.CITATION_NAMESPACE, EntityType.BOOK, seq)
    private fun noteKey(seq: Long) = EntityKey(EntityKey.CITATION_NAMESPACE, EntityType.NOTE, seq)

    // Typed as UpPacket so `listOf(Versioned(v, packet))` infers List<Versioned<UpPacket>> — the
    // invariant Versioned<T> won't widen a List<Versioned<TelemetryPacket>> to the envelope's type.
    private fun telemetry(key: EntityKey, minutes: Int, occurredAt: Long, sourceId: String? = "prod-1"): UpPacket =
        TelemetryPacket(key, SourceType.OREILLY, "Designing Data-Intensive Applications", minutes, occurredAt, sourceId)

    private fun note(bookKey: EntityKey, key: EntityKey, body: String, createdAt: Long): UpPacket {
        val descriptor = SourceDescriptor(bookKey, SourceType.OREILLY, "isbn-1", "Designing Data-Intensive Applications", "Kleppmann")
        return NotePacket(
            bookKey = bookKey,
            sourceType = SourceType.OREILLY,
            sourceId = "isbn-1",
            note = Note(key, NoteType.FREESTANDING_SYNTHESIS, body, descriptor, emptyList(), createdAt)
        )
    }

    /** Write a Citation outbound envelope (what CitationRepository.sync would drop) into [dir]. */
    private fun writeOutbound(dir: File, packets: List<Mailbox.Versioned<UpPacket>>) {
        FileEnvelopeStore(dir).writeOutbound(OutboundEnvelope(EntityKey.CITATION_NAMESPACE, packets, ackedIntentVersion = 0))
    }

    private class Cursor(var value: Long = 0)

    private fun repo(dir: File, dao: FakeBookDao, cursor: Cursor) =
        CitationSyncRepository(
            BookRepository(dao),
            dir,
            readAckedVersion = { cursor.value },
            writeAckedVersion = { cursor.value = it }
        )

    @Test
    fun `ingests telemetry into a freshly created book`() = runTest {
        val dir = tmp.newFolder("sync")
        val dao = FakeBookDao()
        val cursor = Cursor()
        writeOutbound(dir, listOf(Mailbox.Versioned(1, telemetry(bookKey(3), minutes = 45, occurredAt = 1_700_000_000_000))))

        val summary = repo(dir, dao, cursor).sync()

        assertEquals(1, summary.telemetryIngested)
        assertEquals(1L, summary.ackedThrough)
        assertEquals(1L, cursor.value)
        // The book Citation knew about but LifeOps had never seen is created, tagged, marked READING.
        val book = dao.books.single()
        assertEquals("ER-Book-3", book.id)
        assertEquals("READING", book.status)
        assertEquals(SourceType.OREILLY.name, book.sourceType)
        // The GUI title is seeded from Citation's title, and the Citation record retains it too.
        assertEquals("Designing Data-Intensive Applications", book.title)
        assertEquals("Designing Data-Intensive Applications", book.citationTitle)
        // Even a read-only session (telemetry, no notes) keeps O'Reilly's id.
        assertEquals("prod-1", book.sourceId)
        // And the engaged minutes land as a time entry (this is what Reports/week-close read).
        assertEquals(45, dao.times.single().durationMinutes)
    }

    @Test
    fun `a user-renamed title survives re-sync while the Citation record refreshes`() = runTest {
        val dao = FakeBookDao()
        val repo = BookRepository(dao)
        // First sync creates the book from Citation's title.
        repo.ingestReadingTelemetry("ER-Book-3", "Citation Title", SourceType.OREILLY.name, 20, 1L, sourceId = "prod-9")
        // The user renames it in the LifeOps GUI.
        val created = dao.getById("ER-Book-3")!!.toModel()
        repo.updateBook(created, title = "My Own Title", author = "Kleppmann")
        // A later sync arrives (e.g. Citation corrected its title / logged more time).
        repo.ingestReadingTelemetry("ER-Book-3", "Citation Title v2", SourceType.OREILLY.name, 15, 2L, sourceId = "prod-9")

        val book = dao.getById("ER-Book-3")!!
        assertEquals("My Own Title", book.title)          // GUI title untouched by sync
        assertEquals("Kleppmann", book.author)            // user edits preserved
        assertEquals("Citation Title v2", book.citationTitle) // Citation record refreshed
        assertEquals("prod-9", book.sourceId)
        assertEquals(35, dao.times.sumOf { it.durationMinutes })
    }

    @Test
    fun `ingests a note and creates the book if none exists`() = runTest {
        val dir = tmp.newFolder("sync")
        val dao = FakeBookDao()
        val cursor = Cursor()
        writeOutbound(dir, listOf(Mailbox.Versioned(1, note(bookKey(3), noteKey(7), "The log is the source of truth", 1_700_000_000_000))))

        val summary = repo(dir, dao, cursor).sync()

        assertEquals(1, summary.notesIngested)
        // The book is created and keeps O'Reilly's id as its Citation record.
        val book = dao.books.single()
        assertEquals("ER-Book-3", book.id)
        assertEquals("isbn-1", book.sourceId)
        assertEquals("Designing Data-Intensive Applications", book.citationTitle)
        val stored = dao.notes.single()
        assertEquals("The log is the source of truth", stored.content)
        // Row id derives from Citation's note key, keeping re-syncs idempotent.
        assertEquals("citation:ER-Note-7", stored.id)
    }

    @Test
    fun `re-syncing the same envelope is a no-op past the cursor`() = runTest {
        val dir = tmp.newFolder("sync")
        val dao = FakeBookDao()
        val cursor = Cursor()
        writeOutbound(dir, listOf(
            Mailbox.Versioned(1, telemetry(bookKey(3), 45, 1_700_000_000_000)),
            Mailbox.Versioned(2, note(bookKey(3), noteKey(7), "note body", 1_700_000_000_000))
        ))

        repo(dir, dao, cursor).sync()
        // Second round: Citation still resends the same unacked packets, but the cursor is past them.
        val second = repo(dir, dao, cursor).sync()

        assertEquals(0, second.telemetryIngested)
        assertEquals(0, second.notesIngested)
        assertEquals(2L, cursor.value)
        assertEquals(1, dao.times.size)   // not double-counted
        assertEquals(1, dao.notes.size)
    }

    @Test
    fun `only packets above the cursor are ingested`() = runTest {
        val dir = tmp.newFolder("sync")
        val dao = FakeBookDao()
        val cursor = Cursor(value = 1)  // packet v1 already ingested previously
        writeOutbound(dir, listOf(
            Mailbox.Versioned(1, telemetry(bookKey(3), 45, 1_700_000_000_000)),
            Mailbox.Versioned(2, telemetry(bookKey(3), 30, 1_700_000_100_000))
        ))

        val summary = repo(dir, dao, cursor).sync()

        assertEquals(1, summary.telemetryIngested)
        assertEquals(30, dao.times.single().durationMinutes)
        assertEquals(2L, cursor.value)
    }

    @Test
    fun `writes an inbound ack Citation can prune against`() = runTest {
        val dir = tmp.newFolder("sync")
        val dao = FakeBookDao()
        val cursor = Cursor()
        writeOutbound(dir, listOf(Mailbox.Versioned(5, telemetry(bookKey(3), 45, 1_700_000_000_000))))

        repo(dir, dao, cursor).sync()

        val inbound = FileEnvelopeStore(dir).readInbound()
        assertNotNull(inbound)
        assertEquals(5L, inbound!!.ackedPacketVersion)
        assertTrue(inbound.intents.isEmpty())
    }

    @Test
    fun `no envelope yet is a clean no-op`() = runTest {
        val dir = tmp.newFolder("sync")
        val dao = FakeBookDao()
        val cursor = Cursor(value = 3)

        val summary = repo(dir, dao, cursor).sync()

        assertEquals(0, summary.telemetryIngested)
        assertEquals(3L, summary.ackedThrough)
        assertTrue(dao.books.isEmpty())
    }
}
