package com.citation.app.data

import com.citation.app.data.db.BookEntity
import com.citation.app.data.opds.map
import com.citation.core.anchor.TextAnchor
import com.citation.core.capture.CaptureBuilder
import com.citation.core.capture.CaptureClusterer
import com.citation.core.capture.CaptureLink
import com.citation.core.capture.CaptureTriage
import com.citation.core.capture.ProvenanceLadder
import com.citation.core.capture.RawCapture
import com.citation.core.key.EntityKey
import com.citation.core.key.EntityType
import com.citation.core.kindle.KindleNotebook
import com.citation.core.manifest.StorageInventory
import com.citation.core.manifest.StorageReport
import com.citation.core.model.SourceType
import com.citation.core.note.Highlight
import com.citation.core.note.HighlightColor
import com.citation.core.note.Note
import com.citation.core.note.NoteResolver
import com.citation.core.sync.AcquireBookIntent
import com.citation.core.sync.BookLifecycle
import com.citation.core.sync.FileEnvelopeStore
import com.citation.core.sync.IntentReconciler
import com.citation.core.sync.NotePacket
import com.citation.core.sync.SyncEngine
import com.citation.core.sync.TelemetryPacket
import com.citation.core.sync.UpPacket
import kotlinx.coroutines.flow.map

/**
 * Capture from outside Citation: a PROCESS_TEXT selection, a share, the floating bubble, and a
 * Kindle export import.
 *
 * The hard part is not the text — it is deciding which book it belongs to, and doing so without
 * asking, which is what `BindOrCreate` is handed the candidates for.
 */

/**
 * File a **quoted capture** handed in from another app — a browser selection, a shared passage, a
 * Kindle highlight. The [raw] material is run through the [ProvenanceLadder] to attach the best
 * identifier available (never unassigned), then frozen as a [SourceType.CAPTURE] note anchored by
 * an opaque location token. [annotation] is your optional note *about* the quote (blank ⇒ a plain
 * saved highlight). Fully offline; queued up the mailbox like any other note.
 */
internal suspend fun CitationRepository.captureQuoted(
    raw: RawCapture,
    quote: String,
    annotation: String = "",
    location: String? = raw.location ?: raw.url,
    now: Long = System.currentTimeMillis()
): Note {
    val provenance = ProvenanceLadder.resolve(raw)
    val highlight = CaptureBuilder.highlight(keys.next(EntityType.HIGHLIGHT), provenance, quote, location, now)
    val note = CaptureBuilder.quotedNote(keys.next(EntityType.NOTE), highlight, annotation, now)
    persistCapture(highlight, note)
    return note
}

/**
 * File a **manual quick-capture** — text you typed over some app via the floating bubble, with
 * nothing on screen to quote. Lands as a freestanding note whose provenance is whatever the ladder
 * could attach (often just the app package or a timestamp — i.e. thin, and headed for triage).
 */
internal suspend fun CitationRepository.captureManual(raw: RawCapture, now: Long = System.currentTimeMillis()): Note {
    val provenance = ProvenanceLadder.resolve(raw)
    val note = CaptureBuilder.manualNote(keys.next(EntityType.NOTE), provenance, raw.text, now)
    val versioned = mailbox.post(NotePacket.of(note))
    db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
    checkpointKey(EntityType.NOTE)
    persistSyncState()
    return note
}

/**
 * Import a Kindle **notebook export** (HTML). Each highlight/note becomes a provisional capture on
 * the book's title cluster (the export carries no ISBN), so they behave like any other capture and
 * get promoted to the real book when you add it properly. Non-realtime and bounded by Amazon's
 * per-book clipping limit — this ingests exactly what the file contains. Returns the count filed,
 * or `null` if the HTML isn't a Kindle notebook.
 */
internal suspend fun CitationRepository.importKindleNotebook(html: String, now: Long = System.currentTimeMillis()): Int? {
    val export = KindleNotebook.parse(html) ?: return null
    val provenance = export.provenance(now)
    var filed = 0
    export.entries.forEach { entry ->
        if (entry.isStandaloneNote) {
            val note = CaptureBuilder.manualNote(keys.next(EntityType.NOTE), provenance, entry.annotation.orEmpty(), now)
            val versioned = mailbox.post(NotePacket.of(note))
            db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
        } else {
            val highlight = CaptureBuilder.highlight(
                keys.next(EntityType.HIGHLIGHT), provenance, entry.quote, entry.locationToken, now
            )
            val note = CaptureBuilder.quotedNote(keys.next(EntityType.NOTE), highlight, entry.annotation.orEmpty(), now)
            persistCapture(highlight, note)
        }
        filed++
    }
    checkpointKey(EntityType.NOTE)
    checkpointKey(EntityType.HIGHLIGHT)
    persistSyncState()
    return filed
}

/**
 * Edit a note's body — the way you **add your own words onto a captured quote**. A capture lands
 * with an empty body (just the quote + its source); this lets you annotate it afterward. The
 * edited note is re-posted up the mailbox so LifeOps sees the new text. Returns the updated note,
 * or `null` if the key is unknown.
 */
internal suspend fun CitationRepository.editNoteBody(noteKey: String, body: String): Note? {
    val entity = db.noteDao().get(noteKey) ?: return null
    val updated = CitationMappers.noteFromEntity(entity).copy(body = body)
    val versioned = mailbox.post(NotePacket.of(updated))
    db.noteDao().upsert(CitationMappers.noteToEntity(updated, versioned.version))
    persistSyncState()
    return updated
}

/**
 * Set a note's tags — the local organizational layer. Unlike [editNoteBody] this does **not**
 * re-post up the mailbox: tags never travel on the sync wire (a note's *text* is the shared
 * artifact; its filing is Citation's own), so we persist locally and preserve the existing
 * `syncVersion` untouched. [tags] is stored as-is — callers normalize via `Tags.parse` first.
 */
internal suspend fun CitationRepository.setNoteTags(noteKey: String, tags: List<String>): Note? {
    val entity = db.noteDao().get(noteKey) ?: return null
    val updated = CitationMappers.noteFromEntity(entity).copy(tags = tags)
    db.noteDao().upsert(CitationMappers.noteToEntity(updated, entity.syncVersion))
    return updated
}

/**
 * Recolour a note's highlight — filing, exactly like [setNoteTags], and off the sync wire for
 * the same reason: what a highlight *says* is the note's text and travels; what it looks like on
 * your page is yours. The existing sync version is preserved so recolouring never re-posts.
 */
internal suspend fun CitationRepository.setNoteHighlight(noteKey: String, highlight: HighlightColor): Note? {
    val entity = db.noteDao().get(noteKey) ?: return null
    val updated = CitationMappers.noteFromEntity(entity).copy(highlightColor = highlight)
    db.noteDao().upsert(CitationMappers.noteToEntity(updated, entity.syncVersion))
    return updated
}

/**
 * Report **engaged** reading time for a book up the mailbox as a [TelemetryPacket]. The engaged
 * minutes are measured by the reader's [com.citation.core.reader.ReadingMeter] (idle dwell already
 * excluded), so LifeOps receives honest time. The packet carries the book's `sourceType` so
 * LifeOps can map it to a category (O'Reilly → Learning, Royal Road → Fun) without holding the
 * book. No-op for a zero-minute or unknown book.
 */
internal suspend fun CitationRepository.recordReadingTelemetry(
    bookKey: String,
    minutes: Int,
    occurredAt: Long = System.currentTimeMillis()
) {
    if (minutes <= 0) return
    val entity = db.bookDao().get(bookKey) ?: return
    val packet = TelemetryPacket(
        bookKey = EntityKey.parse(bookKey),
        sourceType = SourceType.valueOf(entity.sourceType),
        title = entity.title,
        minutesRead = minutes,
        occurredAt = occurredAt,
        // Carry the source's own id (O'Reilly product id / ISBN / RR id) so LifeOps keeps the
        // book's real identity even from a read-only session with no notes.
        sourceId = entity.sourceId
    )
    mailbox.post(packet)
    persistSyncState()
}

/** Persist a captured highlight + its note and queue the note up the mailbox. */
internal suspend fun CitationRepository.persistCapture(highlight: Highlight, note: Note) {
    val versioned = mailbox.post(NotePacket.of(note))
    db.highlightDao().upsert(CitationMappers.highlightToEntity(highlight))
    db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
    checkpointKey(EntityType.HIGHLIGHT)
    checkpointKey(EntityType.NOTE)
    persistSyncState()
}

/** All provisional-source clusters over captured notes — the retroactive grouping view. */
internal suspend fun CitationRepository.provisionalSources(): List<CaptureClusterer.ProvisionalSource> =
    CaptureClusterer.clusterNotes(db.noteDao().captures().map(CitationMappers::noteFromEntity))

/** The thin-context triage queue: captures whose best identifier is only an app name/timestamp. */
internal suspend fun CitationRepository.triageQueue(): List<CaptureClusterer.ProvisionalSource> =
    CaptureTriage.queue(db.noteDao().captures().map(CitationMappers::noteFromEntity))

/**
 * **Manually link** a capture to a book you already have — the user-driven counterpart to
 * [promoteCapturesTo]. When a capture never got a hard identity (a browser clip, a floating thought,
 * a Kindle-notebook highlight), promotion can't claim it; this is how you say "this *is* from that
 * book on my shelf." Binds every still-provisional note in [noteKey]'s cluster to [bookKey] — so
 * linking one member captures the whole provisional source, exactly as automatic promotion would —
 * re-posts each up the mailbox so LifeOps sees the binding, and returns the number of notes linked.
 * No fuzzy matching: the user has chosen the book. Returns 0 if the note or book is unknown, or the
 * note carries no cluster id to link.
 */
internal suspend fun CitationRepository.linkNoteToBook(noteKey: String, bookKey: String): Int {
    db.bookDao().get(bookKey) ?: return 0
    val entity = db.noteDao().get(noteKey) ?: return 0
    val clusterId = CitationMappers.noteFromEntity(entity).source.sourceId ?: return 0
    val captures = db.noteDao().captures().map(CitationMappers::noteFromEntity)
    val rebound = CaptureLink.link(captures, clusterId, EntityKey.parse(bookKey))
    rebound.forEach { note ->
        val versioned = mailbox.post(NotePacket.of(note))
        db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
    }
    if (rebound.isNotEmpty()) persistSyncState()
    return rebound.size
}

/**
 * Resolve a note's references against the current state of its source, yielding each reference's
 * degradation state (resolved / fuzzy / orphaned / source-unavailable) and jump target. This is
 * how the Notes UI shows whether a note can still jump to live context or only display its frozen
 * snapshot.
 */
internal suspend fun CitationRepository.resolveNote(note: Note): List<NoteResolver.RefResolution> {
    // Pre-load the chapter texts the note's anchors need, keyed by chapter ordinal.
    val bookKey = note.source.bookKey?.toString()
    val entity = bookKey?.let { db.bookDao().get(it) }
    val textByOrdinal: Map<Int, String> = when {
        entity == null -> emptyMap()
        entity.sourceType == SourceType.ROYAL_ROAD.name -> {
            val fictionId = entity.sourceId ?: ""
            note.references.mapNotNull { (it.anchor as? TextAnchor.Flowing)?.chapterOrdinal }
                .distinct()
                .mapNotNull { ord -> files.readBorrowedChapter(fictionId, ord)?.let { ord to it } }
                .toMap()
        }
        else -> db.chapterDao().forBook(entity.key)
            .mapNotNull { ch -> ch.text?.let { ch.ordinal to it } }.toMap()
    }
    return NoteResolver.resolveNote(note) { anchor ->
        (anchor as? TextAnchor.Flowing)?.let { textByOrdinal[it.chapterOrdinal] }
    }
}

/** Outbound packets LifeOps hasn't acknowledged yet — what a sync pass would deliver. */
internal fun CitationRepository.pendingUpPackets(): List<UpPacket> = mailbox.outboxSince(0).map { it.payload }

/**
 * Build the storage picture: per-item footprints tagged by recoverability (borrowed serials
 * read as reclaimable, owned files + notes as irreplaceable). **Visibility only** — this reports,
 * it never prunes.
 */
internal suspend fun CitationRepository.storageReport(): StorageReport {
    val items = ArrayList<StorageInventory.StorageItem>()
    db.bookDao().getAll().forEach { b ->
        val bytes = when (b.sourceType) {
            SourceType.PDF.name -> files.ownedFileSize(b.key, "pdf")
            // AO3 keeps its downloaded EPUB in the owned store, sized like any EPUB.
            SourceType.EPUB.name, SourceType.AO3.name -> files.ownedFileSize(b.key, "epub")
            SourceType.ROYAL_ROAD.name -> b.sourceId?.let { files.borrowedTotalSize(it) } ?: 0L
            else -> 0L // O'Reilly caches nothing; INTERNAL "wanted" has no content yet
        }
        if (bytes > 0) {
            items.add(
                StorageInventory.StorageItem(
                    label = "${b.title} (${b.sourceType.lowercase()})",
                    sourceType = SourceType.valueOf(b.sourceType),
                    bytes = bytes
                )
            )
        }
    }
    // All notes/highlights are sovereign and irreplaceable — surfaced as one line.
    val noteBytes = db.noteDao().getAllSync().sumOf {
        (it.body.length + it.frozenTitle.length + it.referencesJson.length).toLong()
    }
    if (noteBytes > 0) {
        items.add(StorageInventory.StorageItem("Notes & highlights", SourceType.INTERNAL, noteBytes))
    }
    return StorageInventory.report(items)
}

/**
 * Run one sync round with LifeOps over the file-drop mailbox: write our unacked packets up,
 * then apply LifeOps' response — prune the acked packets and reconcile each acquire intent via
 * **bind-or-create** (matching an existing book, or minting a new `wanted` one). Offline-safe:
 * with no response file yet, it just leaves the outbound envelope for LifeOps to pick up.
 */
internal suspend fun CitationRepository.sync(now: Long = System.currentTimeMillis()): SyncSummary {
    val engine = SyncEngine(mailbox)
    val store = FileEnvelopeStore(java.io.File(files.sovereignDir, "sync"))
    val sent = mailbox.outboxSince(0).size
    store.writeOutbound(engine.buildOutbound())

    val inbound = store.readInbound() ?: return SyncSummary(sent, intentsCreated = 0, intentsKnown = 0)

    // Candidates for bind-or-create, snapshotted before applying (reconcile runs synchronously).
    val candidates = db.bookDao().getAll().map(::toCandidate)
    val toCreate = ArrayList<AcquireBookIntent>()
    var known = 0
    engine.applyInbound(inbound) { intent ->
        when (IntentReconciler.reconcile(intent, candidates)) {
            is IntentReconciler.Outcome.AlreadyKnown -> known++
            is IntentReconciler.Outcome.CreateWanted -> toCreate.add(intent)
        }
    }
    // LifeOps has durably taken every note up to its ack; drop their outbox marker so a later
    // relaunch doesn't re-seed and resend them (mirrors the mailbox prune applyInbound just did).
    if (inbound.ackedPacketVersion > 0) db.noteDao().clearSyncVersionThrough(inbound.ackedPacketVersion)
    // Persist newly-wanted books (a fuzzy, center-authored placeholder on both state machines).
    toCreate.forEach { intent ->
        val key = keys.next(EntityType.BOOK)
        val lifecycle = BookLifecycle.wanted()
        db.bookDao().upsert(
            BookEntity(
                key = key.toString(),
                title = intent.title,
                author = intent.author,
                sourceType = SourceType.INTERNAL.name,
                sourceId = null,
                language = null,
                acquisitionState = lifecycle.acquisition.name,
                readingState = lifecycle.reading.name,
                createdAt = now
            )
        )
    }
    checkpointKey(EntityType.BOOK)
    persistSyncState()
    return SyncSummary(sent, intentsCreated = toCreate.size, intentsKnown = known)
}
