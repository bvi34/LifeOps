package com.citation.core.identity

/**
 * A **typed identity key** — one piece of evidence that two records are (or aren't) the same work.
 *
 * Different sources carry different *kinds* of authoritative identity, and they differ in strength:
 * a Royal Road fiction id is authoritative for RR; an ISBN identifies an owned published *edition*;
 * a PDF's content hash proves same *file* but not same *work*. A record carries whatever keys it
 * has (an EPUB might have both an ISBN and, once imported, a file hash), and dedup matches on the
 * **strongest available** shared evidence — see [DedupValidator].
 *
 * Each variant knows how to [matches] another key of its own kind, encoding the subtlety that
 * distinguishes real dedup from naive equality (edition-awareness, hash asymmetry).
 */
sealed interface IdentityKey {
    /** Rank used to pick the strongest shared evidence when two records share several key kinds. */
    val strength: Int

    /**
     * Does this key identify the same work as [other]? Only compares keys of the same concrete
     * type; a mismatched type is never a match (that's the validator's job to sequence).
     */
    fun matches(other: IdentityKey): Boolean

    /**
     * Royal Road fiction id — **authoritative for RR**. Two records with the same fiction id are the
     * same serial, full stop; different ids are different serials.
     */
    data class RoyalRoadId(val fictionId: Long) : IdentityKey {
        override val strength: Int get() = STRENGTH_AUTHORITATIVE
        override fun matches(other: IdentityKey): Boolean =
            other is RoyalRoadId && other.fictionId == fictionId
    }

    /**
     * Archive of Our Own work id — **authoritative for AO3**. Two records with the same work id are
     * the same work, full stop; different ids are different works. The AO3 analogue of
     * [RoyalRoadId], kept a distinct type so an AO3 work and a Royal Road fiction that happen to
     * share a numeric id never collide.
     */
    data class Ao3Id(val workId: Long) : IdentityKey {
        override val strength: Int get() = STRENGTH_AUTHORITATIVE
        override fun matches(other: IdentityKey): Boolean =
            other is Ao3Id && other.workId == workId
    }

    /**
     * ISBN for an owned published book — **edition-aware**. The whole point: *Designing
     * Data-Intensive Applications* 1st ed and 2nd ed are distinct works with distinct ISBNs, and a
     * note anchored in the 2nd must not silently rebind to the 1st. So the match is exact on the
     * normalised ISBN, never a fuzzy title collapse.
     *
     * [normalized] strips hyphens/spaces and upper-cases the check digit; ISBN-10 and ISBN-13 forms
     * of the *same edition* are treated as equal via [isbn13].
     */
    data class Isbn(val raw: String) : IdentityKey {
        val normalized: String = raw.filter { it.isLetterOrDigit() }.uppercase()

        /** ISBN-13 form (converts a valid ISBN-10 by the standard 978-prefix rule) for cross-form equality. */
        val isbn13: String? = toIsbn13(normalized)

        override val strength: Int get() = STRENGTH_EDITION
        override fun matches(other: IdentityKey): Boolean {
            if (other !is Isbn) return false
            val a = isbn13
            val b = other.isbn13
            // Prefer edition-normalised comparison; fall back to raw-normalised when a form is invalid.
            return if (a != null && b != null) a == b else normalized == other.normalized
        }
    }

    /**
     * SHA-256 of a PDF's bytes — **proves same *file*, not same *work***. A strong *positive*
     * (identical bytes ⇒ unquestionably the same file) but a weak *negative*: a re-download, a
     * re-export, or an OCR pass yields a different hash for the same book. So a hash match is
     * decisive, a hash *mismatch* proves nothing on its own.
     */
    data class PdfSha(val sha256: String) : IdentityKey {
        val normalized: String = sha256.trim().lowercase()
        override val strength: Int get() = STRENGTH_FILE
        override fun matches(other: IdentityKey): Boolean =
            other is PdfSha && other.normalized == normalized
    }

    companion object {
        const val STRENGTH_AUTHORITATIVE = 3 // RR fiction id: definitive for its source
        const val STRENGTH_EDITION = 2       // ISBN: identifies a specific edition/work
        const val STRENGTH_FILE = 1          // PDF SHA: identifies a file, weak negative

        /** Convert a normalised ISBN-10/13 to ISBN-13, or `null` if it isn't a well-formed ISBN. */
        internal fun toIsbn13(normalized: String): String? = when (normalized.length) {
            13 -> if (normalized.all { it.isDigit() }) normalized else null
            10 -> isbn10ToIsbn13(normalized)
            else -> null
        }

        private fun isbn10ToIsbn13(isbn10: String): String? {
            // First 9 chars are digits; the 10th may be 'X'. We only need the first 9 to form the 13.
            val body = isbn10.take(9)
            if (!body.all { it.isDigit() }) return null
            val core = "978$body"
            val checksum = isbn13Checksum(core)
            return core + checksum
        }

        private fun isbn13Checksum(twelveOrMore: String): Char {
            val digits = twelveOrMore.take(12).map { it - '0' }
            val sum = digits.mapIndexed { i, d -> if (i % 2 == 0) d else d * 3 }.sum()
            val check = (10 - (sum % 10)) % 10
            return '0' + check
        }
    }
}
