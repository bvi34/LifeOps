package com.citation.core.anchor

/**
 * A **typed anchor** — where in a source a note is attached, resolved per format.
 *
 * Flowing formats (EPUB, Royal Road) and fixed formats (PDF) anchor fundamentally differently, so
 * the anchor is a sealed type rather than one leaky shape. What every anchor shares is a *quote*:
 * the exact passage text at capture time, which is what makes re-resolution robust — offsets rot,
 * quotes survive (see [com.citation.core.anchor.FuzzyAnchor]). Royal Road authors edit chapters
 * and owned files get re-exported, so an anchor that trusted only character offsets would break on
 * the first edit; anchoring on the quote lets the note re-find its home even after the text shifts.
 */
sealed interface TextAnchor {
    /** The exact passage text at capture time. The primary, format-independent locator. */
    val quote: String

    /**
     * Anchor into flowing text (EPUB / RR): a chapter ordinal plus a *hint* offset and the quote.
     * The offset is only a starting guess for re-resolution — [FuzzyAnchor] re-finds the quote if
     * the text moved.
     *
     * @property chapterOrdinal which chapter (0-based) the passage lives in.
     * @property approxStart best-known character offset of the quote at capture time (a hint).
     * @property prefix a few chars before the quote, disambiguating repeated quotes.
     * @property suffix a few chars after the quote, likewise.
     */
    data class Flowing(
        val chapterOrdinal: Int,
        val approxStart: Int,
        override val quote: String,
        val prefix: String = "",
        val suffix: String = ""
    ) : TextAnchor

    /**
     * Anchor into a fixed PDF page: a page number, the selection quads (rectangles over the glyph
     * runs), and the extracted quote. Positioned glyphs don't reflow, so the page + quads are the
     * reliable locator; the quote still travels for legibility and for re-export resilience.
     *
     * @property page 0-based page index.
     * @property quads selection rectangles in PDF user space (x0,y0,x1,y1), one per line run.
     */
    data class Pdf(
        val page: Int,
        val quads: List<Quad>,
        override val quote: String
    ) : TextAnchor

    /** A selection rectangle in PDF user-space coordinates. */
    data class Quad(val x0: Float, val y0: Float, val x1: Float, val y1: Float)
}
