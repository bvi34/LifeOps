package com.citation.core.sync

import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import com.citation.core.note.Note

/**
 * The payloads Citation sends **up** the spine to LifeOps. Each is self-describing — it carries
 * `{sourceType, sourceId, frozen-context}` so LifeOps can file and display it *without ever holding
 * the source*. LifeOps is a peer that never opens a book; the packet has to be legible on its own.
 */
sealed interface UpPacket {
    /** The book this packet concerns, by key, so LifeOps can correlate across packets. */
    val bookKey: EntityKey?
}

/**
 * Fulfillment telemetry: you read this book for this long. Feeds LifeOps' time/among-aspects
 * accounting. Deliberately minimal — reading is an activity LifeOps logs, not content it stores.
 *
 * @property sourceType the source kind (EPUB / PDF / ROYAL_ROAD / OREILLY), so LifeOps can map the
 *   reading to a category (e.g. O'Reilly → Learning, Royal Road → Fun) without opening the book.
 * @property minutesRead **engaged** minutes in this reporting window (idle dwell excluded — see
 *   [com.citation.core.reader.ReadingMeter]).
 * @property occurredAt epoch millis of the reading session (window end).
 * @property title frozen for display.
 */
data class TelemetryPacket(
    override val bookKey: EntityKey?,
    val sourceType: SourceType,
    val title: String,
    val minutesRead: Int,
    val occurredAt: Long
) : UpPacket

/**
 * A note/highlight, synthesised for LifeOps. The whole [Note] rides along (it is already frozen and
 * self-sufficient), plus the flattened source triple so LifeOps can index it without understanding
 * Citation's internals.
 *
 * @property sourceType the source kind.
 * @property sourceId the source's own id (RR fiction id / ISBN / file hash), stringified.
 * @property note the frozen note, snapshot and anchor included.
 */
data class NotePacket(
    override val bookKey: EntityKey?,
    val sourceType: SourceType,
    val sourceId: String?,
    val note: Note
) : UpPacket {
    companion object {
        /** Build a note packet, pulling the source triple off the note's frozen descriptor. */
        fun of(note: Note): NotePacket = NotePacket(
            bookKey = note.source.bookKey,
            sourceType = note.source.sourceType,
            sourceId = note.source.sourceId,
            note = note
        )
    }
}

/**
 * The intent Citation consumes **down** the spine: a LifeOps-center-authored request to acquire a
 * book. It is born *fuzzy* — center only knows a title and maybe an author — and the reader's job is
 * to resolve it to a concrete artifact (see [BindOrCreate]).
 *
 * @property intentKey the center's key for this todo (e.g. `LO-Book-42`), so acknowledgements
 *   correlate back.
 * @property title the wanted title (fuzzy).
 * @property author the wanted author (fuzzy, may be null).
 */
data class AcquireBookIntent(
    val intentKey: EntityKey,
    val title: String,
    val author: String?
)
