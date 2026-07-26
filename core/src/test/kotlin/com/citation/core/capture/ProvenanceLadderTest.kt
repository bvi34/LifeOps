package com.citation.core.capture

import com.citation.core.identity.IdentityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceLadderTest {

    private val now = 1_690_000_000_000L

    @Test
    fun bookIdentityIsTheHardTopRung() {
        val p = ProvenanceLadder.resolve(
            RawCapture(
                text = "a quoted sentence",
                bookIdentity = IdentityKey.Isbn("9781449373320"),
                title = "Designing Data-Intensive Applications",
                url = "https://example.com/ddia", // present, but identity outranks it
                capturedAt = now
            )
        )
        assertEquals(ProvenanceRung.BOOK_IDENTITY, p.rung)
        assertEquals(ProvenanceStrength.HARD, p.strength)
        assertEquals("book:isbn:9781449373320", p.clusterId)
        assertFalse(p.isThin)
    }

    @Test
    fun urlBeatsFilenameAppAndTimestamp() {
        val p = ProvenanceLadder.resolve(
            RawCapture(
                text = "clip",
                url = "https://blog.example.com/post",
                filename = "post.pdf",
                appPackage = "com.android.chrome",
                capturedAt = now
            )
        )
        assertEquals(ProvenanceRung.URL, p.rung)
        assertEquals(ProvenanceStrength.PROVISIONAL, p.strength)
        assertEquals("url:https://blog.example.com/post", p.clusterId)
        assertFalse(p.isThin)
    }

    @Test
    fun filenameBeatsAppAndTimestamp() {
        val p = ProvenanceLadder.resolve(
            RawCapture(text = "clip", filename = "chapter3.pdf", appPackage = "com.foo", capturedAt = now)
        )
        assertEquals(ProvenanceRung.FILENAME, p.rung)
        assertEquals("file:chapter3.pdf", p.clusterId)
    }

    @Test
    fun appPackageIsThinButAssigned() {
        val p = ProvenanceLadder.resolve(
            RawCapture(text = "a thought", appPackage = "com.opaque.reader", capturedAt = now)
        )
        assertEquals(ProvenanceRung.APP_PACKAGE, p.rung)
        assertEquals("app:com.opaque.reader", p.clusterId)
        assertTrue("app-only provenance is thin", p.isThin)
    }

    @Test
    fun timestampIsTheGuaranteedFloor() {
        // The worst case: nothing but text. Never unassigned — lands on the timestamp rung.
        val p = ProvenanceLadder.resolve(RawCapture(text = "bare note", capturedAt = now))
        assertEquals(ProvenanceRung.TIMESTAMP, p.rung)
        assertEquals("ts:$now", p.clusterId)
        assertTrue(p.isThin)
    }

    @Test
    fun blankOptionalsFallThrough() {
        // Empty strings must not be treated as present — they should fall to the next rung.
        val p = ProvenanceLadder.resolve(
            RawCapture(text = "x", url = "  ", filename = "", appPackage = "  ", capturedAt = now)
        )
        assertEquals(ProvenanceRung.TIMESTAMP, p.rung)
    }

    @Test
    fun displayTitlePrefersTitleThenOriginThenSnippet() {
        assertEquals(
            "My Title",
            ProvenanceLadder.resolve(RawCapture("body", title = "My Title", capturedAt = now)).displayTitle
        )
        assertEquals(
            "https://x.example/y",
            ProvenanceLadder.resolve(RawCapture("body", url = "https://x.example/y", capturedAt = now)).displayTitle
        )
        // No title/origin at all → a snippet of the text so the row is never blank.
        assertEquals(
            "just some captured words",
            ProvenanceLadder.resolve(RawCapture("  just some captured words  ", capturedAt = now)).displayTitle
        )
    }

    @Test
    fun longSnippetIsEllipsized() {
        val long = "word ".repeat(40)
        val title = ProvenanceLadder.resolve(RawCapture(long, capturedAt = now)).displayTitle
        assertTrue(title.length <= 60)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun clusterIdRoundTripsBookIdentity() {
        val isbn = IdentityKey.Isbn("978-1449373320")
        val cid = ClusterId.ofBookIdentity(isbn)
        val back = ClusterId.bookIdentityOf(cid)
        assertTrue(back is IdentityKey.Isbn)
        // Normalised form survives the round trip.
        assertEquals((isbn).normalized, (back as IdentityKey.Isbn).normalized)

        val rr = IdentityKey.RoyalRoadId(12345)
        assertEquals(rr, ClusterId.bookIdentityOf(ClusterId.ofBookIdentity(rr)))
    }

    @Test
    fun nonBookClusterIdsHaveNoIdentity() {
        assertNull(ClusterId.bookIdentityOf("url:https://x.example"))
        assertNull(ClusterId.bookIdentityOf("ts:123"))
    }

    @Test
    fun rungIsRecoverableFromClusterId() {
        assertEquals(ProvenanceRung.URL, ProvenanceRung.ofClusterId("url:https://x.example"))
        assertEquals(ProvenanceRung.BOOK_IDENTITY, ProvenanceRung.ofClusterId("book:isbn:9781449373320"))
        assertEquals(ProvenanceRung.APP_PACKAGE, ProvenanceRung.ofClusterId("app:com.foo"))
        assertNull(ProvenanceRung.ofClusterId("garbage-with-no-tag"))
    }
}
