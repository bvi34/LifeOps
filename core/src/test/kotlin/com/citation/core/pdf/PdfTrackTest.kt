package com.citation.core.pdf

import com.citation.core.anchor.TextAnchor
import com.citation.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTrackTest {

    private val letter = PdfTrack.PageSize(widthPt = 612f, heightPt = 792f)

    @Test
    fun pdfUsesItsOwnPagedTrack() {
        assertEquals(PdfTrack.RenderTrack.PDF_PAGED, PdfTrack.trackFor(SourceType.PDF))
        assertEquals(PdfTrack.RenderTrack.FLOWING, PdfTrack.trackFor(SourceType.EPUB))
        assertEquals(PdfTrack.RenderTrack.FLOWING, PdfTrack.trackFor(SourceType.ROYAL_ROAD))
    }

    @Test
    fun viewRectConvertsToUserQuadWithYFlip() {
        val quad = PdfTrack.viewRectToUserQuad(
            left = 100f, top = 50f, right = 300f, bottom = 90f,
            page = letter, renderScale = 2f
        )
        assertEquals(50f, quad.x0, 0.001f)
        assertEquals(150f, quad.x1, 0.001f)
        // y flips about the page height: view-top 50px→767pt (upper), view-bottom 90px→747pt (lower).
        assertEquals(767f, quad.y1, 0.001f)
        assertEquals(747f, quad.y0, 0.001f)
    }

    @Test
    fun quadRoundTripsBackToTheSameViewRectAtSameScale() {
        val quad = PdfTrack.viewRectToUserQuad(120f, 40f, 260f, 110f, letter, renderScale = 1.5f)
        val rect = PdfTrack.userQuadToViewRect(quad, letter, renderScale = 1.5f)
        assertEquals(120f, rect.left, 0.01f)
        assertEquals(40f, rect.top, 0.01f)
        assertEquals(260f, rect.right, 0.01f)
        assertEquals(110f, rect.bottom, 0.01f)
    }

    @Test
    fun quadIsStableAcrossZoom() {
        // Same physical selection captured at 1x, replaced on screen at 3x, lands proportionally.
        val quad = PdfTrack.viewRectToUserQuad(100f, 100f, 200f, 140f, letter, renderScale = 1f)
        val at3x = PdfTrack.userQuadToViewRect(quad, letter, renderScale = 3f)
        assertEquals(300f, at3x.left, 0.01f)
        assertEquals(600f, at3x.right, 0.01f)
    }

    @Test
    fun anchorCarriesPageQuadsAndQuote() {
        val quads = listOf(TextAnchor.Quad(10f, 20f, 30f, 40f))
        val anchor = PdfTrack.anchor(page = 4, quote = "positioned glyphs", quads = quads)
        assertEquals(4, anchor.page)
        assertEquals(quads, anchor.quads)
        assertEquals("positioned glyphs", anchor.quote)
    }

    @Test
    fun sha256IsStableAndIdentityForTheSameBytes() {
        val a = PdfTrack.sha256("hello world".toByteArray())
        val b = PdfTrack.sha256("hello world".toByteArray())
        val c = PdfTrack.sha256("HELLO WORLD".toByteArray())
        assertEquals(a, b)
        assertEquals(64, a.length) // hex-encoded SHA-256
        assert(a != c)
    }
}
