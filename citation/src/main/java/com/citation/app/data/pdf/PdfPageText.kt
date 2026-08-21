package com.citation.app.data.pdf

import android.content.Context
import com.citation.app.data.CitationRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one Android-side step of the PDF reflow: pull the text layer out of an owned PDF, **one page
 * at a time**, so [com.citation.core.pdf.PdfFlow] can rebuild it into a flowing book that still
 * knows which page each passage came from.
 *
 * Per-page extraction is the whole point — a single `getText(doc)` call would hand back one
 * undifferentiated string and the page numbers (the citation) would be gone. Everything that
 * *interprets* the text is pure and lives in `:core`; this stays a thin, replaceable I/O shim, the
 * same shape as `logistics`' `PdfTextExtractor`.
 *
 * Uses the PDFBox-Android port already in the app. Its font/resource loader must be initialized once
 * against a context before the first parse; [ensureInit] handles that idempotently.
 */
class PdfPageText(context: Context) : CitationRepository.PdfTextSource {

    private val appContext = context.applicationContext

    /**
     * Extract [file]'s text, page 0 first. Returns an empty list when the file can't be opened or
     * parsed — a scan with no text layer legitimately yields blank pages, which `PdfFlow` then
     * declines to reflow.
     */
    override suspend fun pages(file: File): List<String> = withContext(Dispatchers.IO) {
        ensureInit(appContext)
        runCatching {
            // Load from the File (not a stream): PDFBox can then page the document off disk instead
            // of holding the whole thing in memory, which matters for a few-hundred-page book.
            PDDocument.load(file).use { doc ->
                val stripper = PDFTextStripper().apply {
                    // Order glyphs by position rather than by their order in the content stream —
                    // that's what makes extracted lines come out in reading order.
                    sortByPosition = true
                }
                (1..doc.numberOfPages).map { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.getText(doc)
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        @Volatile
        private var initialized = false

        fun ensureInit(context: Context) {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        PDFBoxResourceLoader.init(context)
                        initialized = true
                    }
                }
            }
        }
    }
}
