package com.citation.app.ui

import androidx.lifecycle.viewModelScope
import com.citation.app.data.beginReaderContext
import com.citation.app.data.hasPdfFlow
import com.citation.app.data.importPdf
import com.citation.app.data.kindleSession
import com.citation.app.data.oreillySession
import com.citation.app.data.oreillyWarmCacheStale
import com.citation.app.data.paceFor
import com.citation.app.data.pdfSession
import com.citation.app.data.recordPace
import com.citation.app.data.recordReadingTelemetry
import com.citation.core.model.SourceType
import com.citation.core.reader.ReadingPace
import com.citation.core.speech.Resume
import com.citation.core.speech.SavedPlace
import com.citation.core.speech.SpeechSettings
import kotlinx.coroutines.launch

/**
 * Opening a book, and the engaged-reading meter that runs while one is open.
 *
 * The meter is here rather than beside the position because it measures *sessions*, not places: it
 * starts when a book opens and stops when it closes, and every way of opening one — EPUB, PDF,
 * O'Reilly, Kindle, a serial — goes through the same two calls.
 */

/** Start metering engaged reading for [bookKey]; report + close any prior book's session first. */
/** Reset the position/pace state for a newly opened book, and load what we know of its pace. */
internal fun ReaderViewModel.beginPositionTracking(bookKey: String?, chapterOrdinal: Int, charOffset: Int) {
    refreshPerBookFlag(bookKey)
    _position.value = chapterOrdinal to charOffset
    paceCharacters = 0
    _pace.value = ReadingPace()
    if (bookKey == null) return
    viewModelScope.launch { _pace.value = repository.paceFor(bookKey) }
}

internal fun ReaderViewModel.startReadingSession(bookKey: String?) {
    bookKey ?: return
    if (readingBookKey != null && readingBookKey != bookKey) endReadingSession()
    readingBookKey = bookKey
    readingMeter.resume()
}

/** A reading-progress signal (page turn / scroll / chapter advance) from any reader track. */
internal fun ReaderViewModel.onReadingProgress() {
    if (readingBookKey != null) readingMeter.progress()
}

/** Reader became visible again (lifecycle resume): keep accruing. */
internal fun ReaderViewModel.onReaderVisible() {
    if (readingBookKey != null) readingMeter.resume()
}

/**
 * Reader went to the background (lifecycle stop): bank + report engaged time, keep the session.
 *
 * Also the one place [SpeechSettings.continueInBackground] is enforced. Off, the voice is a
 * feature of the page and stops when you leave it; on — the default, and the whole point of
 * listening — it carries on with the screen locked, held up by the foreground service.
 */
internal fun ReaderViewModel.onReaderHidden() {
    reportReading()
    if (!speechSettings.value.continueInBackground && narration.value.isActive) pauseAloud()
}

/** End the current reading session (book closed): report, then clear and drop the sub-minute tail. */
internal fun ReaderViewModel.endReadingSession() {
    reportReading()
    readingBookKey = null
    readingRemainderMillis = 0
}

/** Drain the meter to whole engaged minutes and post telemetry; carry the sub-minute remainder. */
internal fun ReaderViewModel.reportReading() {
    val key = readingBookKey ?: return
    readingMeter.pause()
    val total = readingRemainderMillis + readingMeter.flushMillis()
    readingRemainderMillis = total % 60_000L
    val minutes = (total / 60_000L).toInt()

    // The same engaged milliseconds that make the telemetry honest make the pace honest: this
    // is time the meter already refused to credit if you had stepped away.
    val characters = paceCharacters
    paceCharacters = 0
    if (characters > 0 && total > 0) {
        viewModelScope.launch {
            repository.recordPace(key, characters, total)
            _pace.value = repository.paceFor(key)
        }
    }

    if (minutes > 0) viewModelScope.launch { repository.recordReadingTelemetry(key, minutes) }
}

/**
 * Imports an EPUB. [openAfter] is set when the file arrived as an "open this book" intent from
 * another app: there the point of the tap was to read it, so the import lands in the reader
 * rather than in a status line the user would have to go looking for.
 */
internal fun ReaderViewModel.importEpub(bytes: ByteArray, openAfter: Boolean = false) {
    viewModelScope.launch {
        val book = repository.importEpub(bytes)
        _status.value = if (book != null) {
            "Imported “${book.metadata.title}” (${book.chapters.size} chapters)"
        } else {
            "That file didn’t parse as an EPUB."
        }
        if (openAfter) {
            val key = book?.key
            if (key != null) open(key.toString()) else _importAlert.value = "That file didn’t parse as an EPUB."
        }
    }
}

internal fun ReaderViewModel.open(bookKey: String) {
    viewModelScope.launch {
        // Read cache staleness from the *prior* open time, before markOpened restamps it to now.
        val warmCacheStale = repository.oreillyWarmCacheStale(bookKey)
        repository.markOpened(bookKey) // stamp for the Read tab's resume
        when (repository.sourceTypeOf(bookKey)) {
            SourceType.PDF -> {
                // Pages are the ground truth, so a PDF always opens paged; the reader offers the
                // reflowed text track when one exists (and can build it on demand when it doesn't).
                _pdfInitialPage.value = 0
                _pdfFlowReady.value = repository.hasPdfFlow(bookKey)
                _pdfSession.value = repository.pdfSession(bookKey)
            }
            SourceType.OREILLY ->
                _oreillySession.value = repository.oreillySession(bookKey)?.copy(purgeWarmCache = warmCacheStale)
            SourceType.KINDLE -> {
                _kindleSession.value = repository.kindleSession(bookKey)
                _kindleSession.value?.bookKey?.let { repository.beginReaderContext(it) }
            }
            else -> {
                val result = repository.openBook(bookKey)
                openRrFictionId = result?.rrFictionId
                _openBook.value = result?.book
                // Land where you left off: restore the saved chapter, and stage the scroll offset
                // for the reader to apply on first paint. Royal Road needs its buffer slid to the
                // resumed chapter, so route through goToChapter for that side.
                val lastIndex = (result?.book?.chapters?.lastIndex ?: 0).coerceAtLeast(0)
                // Two places may have been recorded — where you last read, and where the voice
                // got to while the app was in your pocket. Open at whichever is more recent.
                val place = Resume.choose(
                    reading = result?.let { SavedPlace(it.chapterOrdinal, it.charOffset, it.positionSavedAt) },
                    listening = result?.listening
                )
                val savedChapter = (place?.chapterOrdinal ?: 0).coerceIn(0, lastIndex)
                val savedOffset = place?.charOffset ?: 0
                pendingScrollChapter = -1
                pendingScrollOffset = 0
                pendingCanonicalChapter = -1
                pendingCanonicalOffset = 0
                // A listened place is *always* a canonical character offset, so it is staged on
                // the channel the reader resolves rather than the one the scroll reader treats
                // as pixels. A read place is staged exactly as it always was.
                if (place?.canonical == true) {
                    pendingCanonicalChapter = savedChapter
                    pendingCanonicalOffset = savedOffset
                } else {
                    pendingScrollChapter = savedChapter
                    pendingScrollOffset = savedOffset
                }
                pendingEndChapter = -1
                _chapterOrdinal.value = savedChapter
                beginPositionTracking(bookKey, savedChapter, savedOffset)
                if (result?.rrFictionId != null && savedChapter > 0) goToChapter(savedChapter)
            }
        }
        startReadingSession(bookKey)
    }
}

internal fun ReaderViewModel.importPdf(bytes: ByteArray, title: String) {
    viewModelScope.launch {
        val key = repository.importPdf(bytes, title)
        repository.markOpened(key)
        _status.value = "Imported PDF “$title”."
        _pdfSession.value = repository.pdfSession(key)
        startReadingSession(key)
        // The pages are readable immediately; the reflowed text track is built behind them so
        // "Read as text" is ready by the time you look for it (and says so when it isn't).
        if (!repository.hasPdfFlow(key)) reflow(key, announce = true)
    }
}
