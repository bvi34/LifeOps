package com.lifeops.app.data.repository

import com.citation.core.note.Note
import com.citation.core.sync.FileEnvelopeStore
import com.citation.core.sync.InboundEnvelope
import com.citation.core.sync.NotePacket
import com.citation.core.sync.TelemetryPacket
import java.io.File

/**
 * The LifeOps side of the Citation ↔ LifeOps sync seam — the transport the ingestion boundary in
 * [BookRepository] was always waiting for.
 *
 * Citation and LifeOps run in the same Operations Sandbox process and share one `filesDir`, so the
 * seam is a folder both can see: Citation writes its unacked up-packets to `citation-outbound.json`
 * (see `CitationRepository.sync`), and this reads them, folds each into LifeOps' own Book records,
 * and writes back `lifeops-inbound.json` acknowledging what it stored. The monotonic packet version
 * is what makes it exactly-once: we ingest only packets above [readAckedVersion]'s cursor, then
 * advance it, so Citation resending the same unacked packets every round never double-counts.
 *
 * LifeOps authors no acquire-intents yet, so the inbound envelope always carries an empty intent
 * list — it exists only to hand Citation the ack cursor so Citation can prune its outbox.
 *
 * The cursor accessors are plain lambdas (not a repository handle) so the whole round is unit-tested
 * on the JVM against a temp folder, exactly like the `:core` transport tests.
 *
 * @param bookRepository the ingestion sink (upserts books, logs time entries, files notes).
 * @param syncDir the shared mailbox folder — `filesDir/sovereign/sync`, where Citation drops its
 *   outbound envelope.
 * @param readAckedVersion reads LifeOps' persisted ingest cursor (see
 *   [PreferencesRepository.citationSyncAckedVersion]).
 * @param writeAckedVersion persists the advanced cursor.
 */
class CitationSyncRepository(
    private val bookRepository: BookRepository,
    private val syncDir: File,
    private val readAckedVersion: () -> Long,
    private val writeAckedVersion: (Long) -> Unit
) {

    /** Outcome of one sync round, for status/logging. */
    data class Summary(
        val telemetryIngested: Int,
        val notesIngested: Int,
        val ackedThrough: Long
    )

    /**
     * Run one ingestion round: read Citation's outbound envelope, fold every packet above our cursor
     * into Book records (creating books Citation knows about but LifeOps hasn't seen), advance and
     * persist the cursor, and acknowledge back to Citation. A no-op — returning the current cursor —
     * when Citation hasn't written an envelope yet.
     *
     * Notes and telemetry are gated differently. **Telemetry** is not idempotent — each packet is one
     * reading session logged as its own time entry — so it's ingested only above the cursor. **Notes**
     * are idempotent (the row id derives from Citation's note key), so every note packet in the
     * envelope is ingested regardless of the cursor; that's what lets Citation backfill historical
     * notes whose original versions sit *below* the cursor without them being skipped. The cursor
     * still advances to the highest version seen so telemetry is never double-counted, and Citation is
     * acked so it can prune (and, once acked, stop re-seeding those notes).
     *
     * Un-fileable packets (telemetry or a note with no book key) produce no LifeOps row but still
     * advance the cursor so Citation stops resending them.
     */
    suspend fun sync(): Summary {
        val store = FileEnvelopeStore(syncDir)
        val outbound = store.readOutbound()
        val lastAcked = readAckedVersion()
        if (outbound == null) return Summary(0, 0, lastAcked)

        var acked = lastAcked
        var telemetry = 0
        var notes = 0
        try {
            for (versioned in outbound.packets.sortedBy { it.version }) {
                when (val packet = versioned.payload) {
                    is TelemetryPacket -> {
                        val key = packet.bookKey?.toString()
                        // Not idempotent — only count sessions we haven't already logged.
                        if (key != null && versioned.version > lastAcked) {
                            bookRepository.ingestReadingTelemetry(
                                bookKey = key,
                                title = packet.title,
                                sourceType = packet.sourceType.name,
                                minutes = packet.minutesRead,
                                occurredAt = packet.occurredAt
                            )
                            telemetry++
                        }
                    }
                    is NotePacket -> {
                        val key = packet.bookKey?.toString()
                        // Idempotent (keyed by the note's Citation id): always ingest so backfilled
                        // history lands even when its version is below the cursor.
                        if (key != null) {
                            bookRepository.ingestNote(
                                bookKey = key,
                                title = packet.note.source.title,
                                sourceType = packet.sourceType.name,
                                noteKey = packet.note.key.toString(),
                                content = citationNoteContent(packet.note),
                                occurredAt = packet.note.createdAt,
                                sourceId = packet.sourceId
                            )
                            notes++
                        }
                    }
                }
                if (versioned.version > acked) acked = versioned.version
            }
        } finally {
            if (acked != lastAcked) writeAckedVersion(acked)
            // Always hand Citation the current cursor so it can prune its outbox, even on a round that
            // ingested nothing new (e.g. everything was already acked).
            store.writeInbound(InboundEnvelope(intents = emptyList(), ackedPacketVersion = acked))
        }
        return Summary(telemetry, notes, acked)
    }

    /**
     * The text LifeOps files for a Citation note: the highlighted passage(s) it hangs off, quoted,
     * then the user's own note below — so the frozen context travels with the note instead of only its
     * bare body. A highlight with no written note keeps just the quote; a freestanding note keeps just
     * the body.
     */
    private fun citationNoteContent(note: Note): String {
        val quotes = note.references.mapNotNull { it.quotedSnapshot.trim().ifBlank { null } }
        val quoteBlock = quotes.joinToString("\n\n") { "“$it”" }
        val body = note.body.trim()
        return listOf(quoteBlock, body).filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
