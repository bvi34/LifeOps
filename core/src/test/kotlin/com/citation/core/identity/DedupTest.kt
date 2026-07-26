package com.citation.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupTest {

    @Test
    fun royalRoadIdIsAuthoritative() {
        val a = IdentitySet(IdentityKey.RoyalRoadId(21220))
        val b = IdentitySet(IdentityKey.RoyalRoadId(21220))
        val c = IdentitySet(IdentityKey.RoyalRoadId(99999))
        assertEquals(DedupValidator.Verdict.SAME, DedupValidator.compare(a, b))
        assertEquals(DedupValidator.Verdict.DIFFERENT, DedupValidator.compare(a, c))
    }

    @Test
    fun isbnIsEditionAware() {
        // DDIA 1st vs 2nd edition — distinct works, must NOT dedup together.
        val first = IdentitySet(IdentityKey.Isbn("978-1449373320"))
        val second = IdentitySet(IdentityKey.Isbn("978-1098119003"))
        assertEquals(DedupValidator.Verdict.DIFFERENT, DedupValidator.compare(first, second))
    }

    @Test
    fun isbn10AndIsbn13OfSameEditionMatch() {
        // Same edition expressed as ISBN-10 and ISBN-13 → same work.
        val ten = IdentitySet(IdentityKey.Isbn("0-13-235088-2"))
        val thirteen = IdentitySet(IdentityKey.Isbn("978-0-13-235088-4"))
        assertEquals(DedupValidator.Verdict.SAME, DedupValidator.compare(ten, thirteen))
    }

    @Test
    fun pdfShaIsStrongPositive() {
        val a = IdentitySet(IdentityKey.PdfSha("ABCDEF"))
        val b = IdentitySet(IdentityKey.PdfSha("abcdef"))
        assertEquals(DedupValidator.Verdict.SAME, DedupValidator.compare(a, b))
    }

    @Test
    fun pdfShaMismatchIsWeakNegativeNotDifferent() {
        // Different bytes of possibly the same work → UNKNOWN, never DIFFERENT.
        val a = IdentitySet(IdentityKey.PdfSha("aaa"))
        val b = IdentitySet(IdentityKey.PdfSha("bbb"))
        assertEquals(DedupValidator.Verdict.UNKNOWN, DedupValidator.compare(a, b))
    }

    @Test
    fun strongestSharedEvidenceWins() {
        // Records share both an ISBN (edition, matches) and a PDF hash (differs). ISBN outranks.
        val a = IdentitySet(IdentityKey.Isbn("9780132350884"), IdentityKey.PdfSha("aaa"))
        val b = IdentitySet(IdentityKey.Isbn("9780132350884"), IdentityKey.PdfSha("bbb"))
        assertEquals(DedupValidator.Verdict.SAME, DedupValidator.compare(a, b))
        assertTrue(DedupValidator.isSameWork(a, b))
    }

    @Test
    fun noSharedEvidenceIsUnknown() {
        val a = IdentitySet(IdentityKey.Isbn("9780132350884"))
        val b = IdentitySet(IdentityKey.RoyalRoadId(1))
        assertEquals(DedupValidator.Verdict.UNKNOWN, DedupValidator.compare(a, b))
    }

    @Test
    fun identitySetReplacesSameKind() {
        val set = IdentitySet(IdentityKey.PdfSha("aaa")).with(IdentityKey.PdfSha("bbb"))
        assertEquals(1, set.keys.size)
        assertFalse(set.keys.any { it is IdentityKey.PdfSha && it.normalized == "aaa" })
    }
}
