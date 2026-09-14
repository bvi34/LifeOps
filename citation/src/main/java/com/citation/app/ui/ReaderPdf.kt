package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.ReflowResult
import com.citation.app.data.hasPdfFlow
import com.citation.app.data.importKindleNotebook
import com.citation.app.data.pdfSession
import com.citation.app.data.reflowPdf
import com.citation.core.model.SourceType
import com.citation.core.pdf.PdfFlow
import kotlinx.coroutines.launch

/**
 * PDFs: the page-render track, the reflowed-text track, and moving between them.
 */

/** Extract + reflow [bookKey], reporting the outcome. Returns true when a text track now exists. */
internal suspend fun ReaderViewModel.reflow(bookKey: String, announce: Boolean): Boolean {
    if (_reflowing.value) return false // an extraction is already running; don't start a second
    _reflowing.value = true
    val result = try { repository.reflowPdf(bookKey) } finally { _reflowing.value = false }
    val ready = result is ReflowResult.Reflowed
    _pdfFlowReady.value = ready
    if (announce) {
        _status.value = when (result) {
            is ReflowResult.Reflowed ->
                "Text extracted — ${result.pages} page${if (result.pages == 1) "" else "s"} readable as text."
            ReflowResult.NoTextLayer ->
                "No text layer in this PDF (it's a scan) — pages only."
            ReflowResult.Unreadable ->
                "Couldn't read that PDF's text."
        }
    }
    return ready
}

/**
 * Switch the open PDF from rendered pages to its reflowed text, landing on the chapter that holds
 * the page you were looking at. Extracts on demand if the track isn't built yet; a scan with no
 * text layer says so and stays on the pages.
 */
internal fun ReaderViewModel.readPdfAsText(fromPage: Int) {
    val session = _pdfSession.value ?: return
    viewModelScope.launch {
        if (!repository.hasPdfFlow(session.bookKey) && !reflow(session.bookKey, announce = true)) return@launch
        val result = repository.openBook(session.bookKey) ?: return@launch
        val book = result.book
        val chapter = PdfFlow.chapterForPage(book.chapters, fromPage)
        _openBook.value = book
        _chapterOrdinal.value = chapter
        pendingScrollChapter = chapter
        pendingScrollOffset = 0
        _pdfFlowReady.value = true
        _pdfSession.value = null // the flowing reader takes over the screen
    }
}

/**
 * Switch the open reflowed PDF back to its rendered pages — the ground truth, for the figure or
 * table the reflow flattened. [nearOffset] is where the flowing reader was looking, so the paged
 * view opens on that same page.
 */
internal fun ReaderViewModel.readPdfAsPages(nearOffset: Int) {
    val book = _openBook.value ?: return
    val bookKey = book.key?.toString() ?: return
    if (book.metadata.source != SourceType.PDF) return
    val chapter = book.chapterAt(_chapterOrdinal.value)
    val page = chapter?.let { PdfFlow.pageOf(it, nearOffset) } ?: 0
    // Page first, then the session: the paged reader reads the landing page as it composes.
    _pdfInitialPage.value = page
    _openBook.value = null
    _pdfSession.value = repository.pdfSession(bookKey)
}

/**
 * Import a Kindle notebook export (the HTML you get from "Export notebook"). Each highlight/note
 * becomes a provisional capture, promotable to the real book when you add it properly.
 */
internal fun ReaderViewModel.importKindleNotebook(html: String) {
    viewModelScope.launch {
        val count = repository.importKindleNotebook(html)
        _status.value = when {
            count == null -> "That didn’t look like a Kindle notebook export."
            count == 0 -> "No highlights found in that export."
            else -> "Imported $count Kindle highlight${if (count == 1) "" else "s"}."
        }
    }
}
