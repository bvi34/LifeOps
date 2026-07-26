package com.citation.core.capture

/**
 * Walks the [ProvenanceRung] ladder for a [RawCapture] and returns the best identifier available,
 * **never leaving a note unassigned**.
 *
 * The order is fixed and strongest-first: a hard book identity beats a URL beats a filename beats an
 * app package beats a timestamp. The timestamp rung is the guaranteed floor — [RawCapture.capturedAt]
 * is always present — so [resolve] is total: every capture, however thin, gets a [CaptureProvenance].
 *
 * This runs **at capture time and does nothing else**: it attaches provenance and stops. It does not
 * cluster, does not promote, does not ask the user to disambiguate — the whole point is a capture that
 * doesn't make you stop and open Citation. The lossy-but-frictionless captures it produces are cleaned
 * up later, on your terms, by triage and promotion.
 */
object ProvenanceLadder {

    /** Resolve [raw] to the strongest identifier it actually yielded. Total — always succeeds. */
    fun resolve(raw: RawCapture): CaptureProvenance {
        val displayTitle = displayTitleFor(raw)

        raw.bookIdentity?.let {
            return CaptureProvenance(
                rung = ProvenanceRung.BOOK_IDENTITY,
                clusterId = ClusterId.ofBookIdentity(it),
                displayTitle = displayTitle,
                author = raw.author
            )
        }
        raw.url?.takeIf { it.isNotBlank() }?.let {
            return CaptureProvenance(ProvenanceRung.URL, ClusterId.ofUrl(it), displayTitle, raw.author)
        }
        raw.filename?.takeIf { it.isNotBlank() }?.let {
            return CaptureProvenance(ProvenanceRung.FILENAME, ClusterId.ofFilename(it), displayTitle, raw.author)
        }
        raw.title?.takeIf { it.isNotBlank() }?.let {
            return CaptureProvenance(ProvenanceRung.TITLE, ClusterId.ofTitle(it), displayTitle, raw.author)
        }
        raw.appPackage?.takeIf { it.isNotBlank() }?.let {
            return CaptureProvenance(ProvenanceRung.APP_PACKAGE, ClusterId.ofApp(it), displayTitle, raw.author)
        }
        // Floor: a timestamp cluster is a singleton, but it is assigned, legible, and triage-able.
        return CaptureProvenance(
            rung = ProvenanceRung.TIMESTAMP,
            clusterId = ClusterId.ofTimestamp(raw.capturedAt),
            displayTitle = displayTitle,
            author = raw.author
        )
    }

    /**
     * The most legible title we can show without any live source. Prefer an explicit title, then the
     * URL/filename/app that identifies the origin, and only fall back to a snippet of the captured
     * text (so even a bare typed note isn't a blank row in the list).
     */
    private fun displayTitleFor(raw: RawCapture): String = when {
        !raw.title.isNullOrBlank() -> raw.title.trim()
        !raw.url.isNullOrBlank() -> raw.url.trim()
        !raw.filename.isNullOrBlank() -> raw.filename.trim()
        !raw.appPackage.isNullOrBlank() -> raw.appPackage.trim()
        else -> snippet(raw.text)
    }

    /** A short one-line preview of captured text for a display title. */
    private fun snippet(text: String, max: Int = 60): String {
        val oneLine = text.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= max) oneLine else oneLine.take(max - 1).trimEnd() + "…"
    }
}
