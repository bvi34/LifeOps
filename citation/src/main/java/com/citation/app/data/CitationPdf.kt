package com.citation.app.data

import com.citation.app.data.db.BookEntity
import com.citation.core.anchor.TextAnchor
import com.citation.core.capture.CapturePromotion
import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey
import com.citation.core.key.EntityType
import com.citation.core.model.Book
import com.citation.core.model.SourceType
import com.citation.core.note.Highlight
import com.citation.core.note.Note
import com.citation.core.note.SourceDescriptor
import com.citation.core.pdf.PdfFlow
import com.citation.core.sync.AcquisitionState
import com.citation.core.sync.NotePacket
import com.citation.core.sync.ReadingState
import kotlinx.coroutines.flow.map

/**
 * PDFs, over two tracks on the same file: the page renderer, and the reflowed text pulled out
 * of it.
 *
 * Two tracks rather than one because a PDF is both a picture of a page and the words on it: a reader
 * that offers only the first cannot resize type, and one that offers only the second loses the
 * figures.
 */

/**
 * Import a PDF into the **owned** (sovereign) store: hash it for identity, dedup against an
 * existing copy of the same file, persist the bytes, and register a [BookEntity]. Returns the
 * book key. PDFs render as pages (see [PdfSession]), so no flowing chapters are stored.
 */
internal suspend fun CitationRepository.importPdf(
    bytes: ByteArray,
    title: String,
    now: Long = System.currentTimeMillis()
): String {
    val sha = com.citation.core.pdf.PdfTrack.sha256(bytes)
    // PDF SHA is a strong positive: identical bytes ⇒ same file, so dedup rather than re-import.
    db.bookDao().findBySource(sha, SourceType.PDF.name)?.let { return it.key }

    val key = keys.next(EntityType.BOOK)
    files.writeOwned(key.toString(), "pdf", bytes)
    db.bookDao().upsert(
        BookEntity(
            key = key.toString(),
            title = title,
            author = null,
            sourceType = SourceType.PDF.name,
            sourceId = sha,
            language = null,
            acquisitionState = AcquisitionState.ACQUIRED.name,
            readingState = ReadingState.TO_READ.name,
            createdAt = now
        )
    )
    checkpointKey(EntityType.BOOK)
    promoteCapturesTo(
        CapturePromotion.PromotableRecord(key, IdentitySet(IdentityKey.PdfSha(sha)), title, null)
    )
    return key.toString()
}

/** The owned PDF file for [bookKey], for the paged reader to render. */
internal fun CitationRepository.pdfSession(bookKey: String): PdfSession =
    PdfSession(bookKey, java.io.File(files.sovereignDir, "$bookKey.pdf"))

// --- PDF reflow (the second track over the same file) ---------------------------------------

/**
 * Pulls the text layer out of an owned PDF, one page at a time. Implemented in the Android layer
 * (PDFBox); a seam rather than a direct call so the repository stays testable without a device
 * and so a different extractor can be dropped in later.
 */
fun interface PdfTextSource {
    suspend fun pages(file: java.io.File): List<String>

    companion object {
        /** No extractor wired: every PDF stays paged-only. */
        val NONE = PdfTextSource { emptyList() }
    }
}

/**
 * Build the **reflowed text track** for an imported PDF: extract its pages, reflow them into the
 * format-blind [Book] the flowing reader already renders, and store the chapters inline against
 * the existing book record.
 *
 * The PDF file itself is untouched and the paged track stays available — this *adds* a way to
 * read the same book, it doesn't convert it. That matters because reflow is lossy (columns,
 * tables, equations), so the rendered page remains the ground truth you can always fall back to.
 * Re-running it replaces the stored chapters, so a bad reflow can simply be rebuilt.
 *
 * Notes already captured are unaffected: they are anchored by page + quote, not by chapter.
 */
internal suspend fun CitationRepository.reflowPdf(bookKey: String, now: Long = System.currentTimeMillis()): ReflowResult {
    val entity = db.bookDao().get(bookKey) ?: return ReflowResult.Unreadable
    if (entity.sourceType != SourceType.PDF.name) return ReflowResult.Unreadable
    val file = java.io.File(files.sovereignDir, "$bookKey.pdf")
    if (!file.exists()) return ReflowResult.Unreadable

    val pages = pdfText.pages(file)
    if (pages.isEmpty()) return ReflowResult.Unreadable
    val book = PdfFlow.build(pages, entity.title, entity.author)
        ?: return ReflowResult.NoTextLayer

    // Replace any earlier reflow wholesale — chapter ordinals are positional, so a rebuild with
    // fewer chapters must not leave the tail of the old one behind.
    db.chapterDao().deleteForBook(bookKey)
    db.chapterDao().upsertAll(
        book.chapters.map { CitationMappers.chapterToEntity(bookKey, it, storeInline = true, now = now) }
    )
    // The reflow just created the chapter track this book's progress is measured against.
    db.bookDao().recountChapters(bookKey)
    return ReflowResult.Reflowed(chapters = book.chapters.size, pages = pages.size)
}

/** Whether [bookKey] already has a reflowed text track stored (so the reader can offer it). */
internal suspend fun CitationRepository.hasPdfFlow(bookKey: String): Boolean =
    db.chapterDao().cachedOrdinals(bookKey).isNotEmpty()

/**
 * Capture a note on a PDF page. Anchored with a [TextAnchor.Pdf] (page + quads + quote); when the
 * caller has no glyph rectangles it passes an empty [quads] for a page-level anchor.
 */
internal suspend fun CitationRepository.capturePdfNote(
    bookKey: String,
    page: Int,
    quote: String,
    body: String,
    quads: List<TextAnchor.Quad> = emptyList(),
    now: Long = System.currentTimeMillis()
): Note {
    val entity = db.bookDao().get(bookKey) ?: error("no book $bookKey")
    val descriptor = SourceDescriptor(
        bookKey = EntityKey.parse(bookKey),
        sourceType = SourceType.PDF,
        sourceId = entity.sourceId,
        title = entity.title,
        author = entity.author
    )
    val highlight = Highlight(
        key = keys.next(EntityType.HIGHLIGHT),
        source = descriptor,
        quotedSnapshot = quote,
        anchor = com.citation.core.pdf.PdfTrack.anchor(page, quote, quads),
        createdAt = now
    )
    val note = Note.anchored(keys.next(EntityType.NOTE), body, highlight, now)
    val versioned = mailbox.post(NotePacket.of(note))
    db.highlightDao().upsert(CitationMappers.highlightToEntity(highlight))
    db.noteDao().upsert(CitationMappers.noteToEntity(note, versioned.version))
    checkpointKey(EntityType.HIGHLIGHT)
    checkpointKey(EntityType.NOTE)
    persistSyncState()
    return note
}
