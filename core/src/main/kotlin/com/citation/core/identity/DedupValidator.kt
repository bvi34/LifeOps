package com.citation.core.identity

/**
 * The set of identity keys a single record carries, and the rules for deciding whether it is the
 * same work as another record.
 *
 * A record accumulates whatever identity evidence its source and lifecycle provide: an EPUB may
 * carry an ISBN from its metadata; once a PDF of the same book is imported it also gains a
 * [IdentityKey.PdfSha]; an RR serial carries a [IdentityKey.RoyalRoadId]. Matching walks the shared
 * key kinds **strongest-first** and returns the first decisive answer, so an authoritative RR-id or
 * an edition-exact ISBN wins before a weaker file hash is even consulted.
 */
data class IdentitySet(val keys: List<IdentityKey>) {

    constructor(vararg keys: IdentityKey) : this(keys.toList())

    /** The single strongest key present, or `null` when the record has no identity evidence yet. */
    val strongest: IdentityKey? get() = keys.maxByOrNull { it.strength }

    /** Add a key, keeping at most one per concrete kind (a newer value of the same kind replaces). */
    fun with(key: IdentityKey): IdentitySet =
        IdentitySet(keys.filterNot { it::class == key::class } + key)
}

/**
 * Decides whether two [IdentitySet]s denote the same work, honouring each key kind's strength and
 * its positive/negative asymmetry.
 */
object DedupValidator {

    /** The outcome of comparing two records' identity evidence. */
    enum class Verdict {
        /** Shared decisive evidence says same work (RR id, edition-exact ISBN, or identical file). */
        SAME,
        /** Shared authoritative evidence says different works (e.g. distinct ISBNs / RR ids). */
        DIFFERENT,
        /** No comparable evidence — can't tell from identity alone (fall back to fuzzy title/author). */
        UNKNOWN
    }

    /**
     * Compare [a] and [b] on the **strongest evidence they share**.
     *
     * Walk key kinds strongest-first. On the first kind both records carry:
     *  - a positive [IdentityKey.matches] ⇒ [Verdict.SAME];
     *  - a negative on an *authoritative or edition-level* kind ⇒ [Verdict.DIFFERENT] (distinct RR
     *    ids / ISBNs really are different works);
     *  - a negative on the *file* kind (PDF SHA) is a weak negative ⇒ keep looking, then
     *    [Verdict.UNKNOWN] if nothing else decides.
     *
     * With no shared key kind at all, the answer is [Verdict.UNKNOWN].
     */
    fun compare(a: IdentitySet, b: IdentitySet): Verdict {
        val sharedKinds = a.keys
            .filter { ak -> b.keys.any { it::class == ak::class } }
            .sortedByDescending { it.strength }

        for (ak in sharedKinds) {
            val bk = b.keys.first { it::class == ak::class }
            if (ak.matches(bk)) return Verdict.SAME
            // A mismatch is only *decisive* for strong evidence; the PDF hash's weak negative defers.
            if (ak.strength >= IdentityKey.STRENGTH_EDITION) return Verdict.DIFFERENT
        }
        return Verdict.UNKNOWN
    }

    /** Convenience: is this a confident same-work match? */
    fun isSameWork(a: IdentitySet, b: IdentitySet): Boolean = compare(a, b) == Verdict.SAME
}
