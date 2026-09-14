package com.citation.app.data

import com.citation.core.model.Book
import com.citation.core.speech.SavedPlace

/**
 * The vocabulary the repository answers in: what opening a book gave you, what a read-in-place
 * session is, what a download or a re-read came back with, and the one-line summary a shelf shows.
 *
 * Top-level in this package rather than nested in [CitationRepository], because they are the
 * language the whole module speaks — a `BookSummary` is what the shelf renders and what the view
 * model holds, and reaching it through the class that happens to build one said nothing true about
 * it.
 */

/**
 * The result of opening a book from the library: the keyed [Book], its serial id if borrowed, and
 * the reader's **saved position** so a reopen lands where you left off rather than at chapter one.
 */
data class OpenResult(
    val book: Book,
    val rrFictionId: Long?,
    val chapterOrdinal: Int = 0,
    val charOffset: Int = 0,
    /**
     * When the reading position was saved, and where the voice separately got to. The caller
     * hands both to `Resume.choose`, which opens the book at whichever was reached last — so a
     * chapter listened to in a pocket is where you land, not the page you last looked at.
     */
    val positionSavedAt: Long? = null,
    val listening: SavedPlace? = null
)

/** A PDF opened for the paged reader: the owned file on disk (rendered page-by-page, never reflowed). */
data class PdfSession(val bookKey: String, val file: java.io.File)

/** What [reflowPdf] managed to do — the three outcomes the UI has to say something about. */
sealed interface ReflowResult {
    /** The PDF now has a flowing track of [chapters] chapters over [pages] pages. */
    data class Reflowed(val chapters: Int, val pages: Int) : ReflowResult
    /** A scan (or an image-only PDF): no text layer to reflow, so it stays paged-only. */
    data object NoTextLayer : ReflowResult
    /** The file couldn't be read at all (missing, corrupt, or no extractor wired). */
    data object Unreadable : ReflowResult
}

/**
 * A read-in-place session: the deep link to open in O'Reilly's own reader (routed through your
 * library proxy when one is configured), the book id, and — when you've saved a card/PIN — the
 * credentials the reader uses to auto-reauth the library sign-in. [login] is null when no
 * credentials are stored, in which case you sign in by hand as before.
 */
data class OreillySession(
    val bookKey: String,
    val bookId: String,
    val deepLink: String,
    val login: OreillyAccess.Credentials? = null,
    /** True when the warm page cache has gone stale (TTL) and should be dropped on open. */
    val purgeWarmCache: Boolean = false
)

/**
 * A **browse** session: the O'Reilly catalog opened through your library proxy (so the whole skim
 * runs on a library card), plus the card/PIN the WebView uses to auto-reauth the library sign-in —
 * exactly like the reader. This is the O'Reilly equivalent of Browse Royal Road: you find a book by
 * skimming O'Reilly's own catalog, and tapping into one hands it back to be opened read-in-place.
 */
data class OreillyCatalog(
    val startUrl: String,
    val login: OreillyAccess.Credentials? = null
)

/**
 * A Kindle **browse** session: your own library on `read.amazon.com`, opened so you can *pick* a
 * book instead of typing its ASIN + title by hand. The read-in-place counterpart to [OreillyCatalog]
 * — but there's no library proxy or stored credential here (Amazon keeps you signed in via cookies),
 * so the session is just the shelf's URL. Tapping a book hands its ASIN + learned title back to be
 * registered and opened.
 */
data class KindleLibrary(val startUrl: String)

/**
 * A Kindle read-in-place session: the ASIN and the `read.amazon.com` URL to open in Amazon's own
 * Cloud Reader. Unlike O'Reilly there's no library proxy or stored credential — you sign in to
 * Amazon in the WebView and cookies persist the session. Whispersync resumes your position on open,
 * so the URL is the ASIN alone.
 */
data class KindleSession(
    val bookKey: String,
    val asin: String,
    val readerUrl: String
)

/** Outcome of a sync round, for status display. */
data class SyncSummary(val sent: Int, val intentsCreated: Int, val intentsKnown: Int)

/** What happened when the user tapped Download on a catalog entry. */
sealed class AcquireResult {
    /** In the library, and openable. */
    data class Added(val bookKey: String, val title: String) : AcquireResult()

    /** Already held — the catalog entry matched a book on the shelf, so nothing was fetched. */
    data class AlreadyHave(val bookKey: String, val title: String) : AcquireResult()

    /** Downloaded, but Citation has no reader for it. */
    data class UnsupportedFormat(val label: String) : AcquireResult()

    data class Failed(val reason: String) : AcquireResult()
}

/** What a re-read of a book's source file achieved. */
sealed class RefreshResult {
    data class Refreshed(val chapters: Int, val unchanged: Int) : RefreshResult()

    /** This source has no file to re-read — a serial, a read-in-place licence, a PDF. */
    object NotRefreshable : RefreshResult()

    object FileMissing : RefreshResult()
    object Unreadable : RefreshResult()
}

/**
 * What the library and Read tabs show for one book, without loading a chapter. Widened from
 * "a title and two state words" to the shelf metadata a real library screen needs — a cover, a
 * series, subjects, and enough to compute progress.
 */
data class BookSummary(
    val key: String,
    val title: String,
    val author: String?,
    val readingState: String,
    val acquisitionState: String,
    val sourceType: String,
    val lastChapterOrdinal: Int,
    val lastOpenedAt: Long?,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val subjects: List<String> = emptyList(),
    val publisher: String? = null,
    val published: String? = null,
    val description: String? = null,
    /** Absolute path of the extracted cover, or null when the book has none. */
    val coverPath: String? = null,
    val chapterCount: Int = 0,
    val isFavorite: Boolean = false,
    val addedAt: Long = 0,
    /** What the reader last measured in characters, or 0 when it never has. */
    val progressFraction: Float = 0f
) {
    /** "The Expanse #1", or null. */
    val seriesLabel: String?
        get() = series?.let { name ->
            val index = seriesIndex ?: return@let name
            val trimmed = if (index == index.toInt().toFloat()) index.toInt().toString() else index.toString()
            "$name #$trimmed"
        }

    /**
     * How far through: what the reader measured in characters when it has, otherwise a coarse
     * chapter estimate. 0 for a book never opened, 1 for one marked done.
     */
    val progress: Float
        get() = when {
            readingState == "DONE" -> 1f
            lastOpenedAt == null -> 0f
            progressFraction > 0f -> progressFraction.coerceIn(0f, 1f)
            chapterCount <= 0 -> 0f
            else -> ((lastChapterOrdinal + 1).toFloat() / chapterCount).coerceIn(0f, 1f)
        }
}
