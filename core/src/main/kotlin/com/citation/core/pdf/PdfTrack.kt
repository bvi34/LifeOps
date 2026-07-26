package com.citation.core.pdf

import com.citation.core.anchor.TextAnchor
import com.citation.core.model.SourceType
import java.security.MessageDigest

/**
 * The **PDF render path as its own track**. Positioned glyphs don't reflow, so a PDF is never forced
 * through the flowing-text reader — it renders *pages* at a fixed layout, and a highlight is a set
 * of rectangles (**quads**) in the page's own coordinate space, not a character offset.
 *
 * Everything here is pure: page geometry, the view↔PDF coordinate transform that keeps a highlight's
 * quads stable across zoom, anchor construction, and the import identity (a file hash). The Android
 * layer supplies the actual page bitmaps (via `PdfRenderer`) and the on-screen selection rectangles;
 * this decides how they map to a durable anchor.
 */
object PdfTrack {

    /** Which reader track a source uses. PDFs render pages; everything else flows. */
    enum class RenderTrack { FLOWING, PDF_PAGED }

    fun trackFor(source: SourceType): RenderTrack =
        if (source == SourceType.PDF) RenderTrack.PDF_PAGED else RenderTrack.FLOWING

    /** A page's intrinsic size in PDF points (user space), independent of how it's rendered on screen. */
    data class PageSize(val widthPt: Float, val heightPt: Float)

    /**
     * Convert an on-screen selection rectangle to a PDF user-space [TextAnchor.Quad].
     *
     * Two coordinate systems differ: the rendered view has its origin at the **top-left** and is
     * scaled by [renderScale] (rendered pixels per PDF point), while PDF user space has its origin at
     * the **bottom-left**. So x scales down by the scale and y additionally flips about the page
     * height. Storing quads in user space (not view pixels) is what makes a highlight land on the
     * same glyphs after you zoom or re-open at a different size.
     */
    fun viewRectToUserQuad(
        left: Float, top: Float, right: Float, bottom: Float,
        page: PageSize,
        renderScale: Float
    ): TextAnchor.Quad {
        val x0 = left / renderScale
        val x1 = right / renderScale
        // Flip Y: a view-y of 0 is the top (= page.heightPt in user space).
        val yTop = page.heightPt - (top / renderScale)
        val yBottom = page.heightPt - (bottom / renderScale)
        return TextAnchor.Quad(x0 = x0, y0 = yBottom, x1 = x1, y1 = yTop)
    }

    /** Inverse of [viewRectToUserQuad]: place a stored quad back on screen at the current scale. */
    fun userQuadToViewRect(quad: TextAnchor.Quad, page: PageSize, renderScale: Float): ViewRect {
        val left = quad.x0 * renderScale
        val right = quad.x1 * renderScale
        val top = (page.heightPt - quad.y1) * renderScale
        val bottom = (page.heightPt - quad.y0) * renderScale
        return ViewRect(left, top, right, bottom)
    }

    /** A rectangle in rendered-view pixel space (origin top-left). */
    data class ViewRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Build a PDF anchor for a selection on [page] with its extracted [quote] and user-space [quads]. */
    fun anchor(page: Int, quote: String, quads: List<TextAnchor.Quad>): TextAnchor.Pdf =
        TextAnchor.Pdf(page = page, quads = quads, quote = quote)

    /**
     * The import identity of a PDF: the SHA-256 of its bytes. A strong positive (identical bytes are
     * unquestionably the same file) but a weak negative — see [com.citation.core.identity.IdentityKey.PdfSha].
     */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
