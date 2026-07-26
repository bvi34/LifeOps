package com.citation.core.capture

import com.citation.core.note.Note

/**
 * The **unresolved / thin-context view** — the small, honest cost of frictionless capture.
 *
 * Capturing without stopping is lossy by design: sometimes all we could attach was the source app's
 * name, or nothing but a timestamp. Those notes are still saved and still readable, but their context
 * lives only in your head, and heads leak. Triage surfaces exactly those — the [ProvenanceRung.APP_PACKAGE]
 * and [ProvenanceRung.TIMESTAMP] captures that are still unbound — so you can tag them *while you
 * still remember* what they were about.
 *
 * Two rules keep this from becoming a chore. It is **read-only and derived** (a filter over the same
 * clusters, never a separate worklist to maintain), and the upgrade it invites is **optional and
 * later** — triage never runs at capture time and never blocks a save. A note that rots un-triaged is
 * a note you didn't care enough to tag; it was never going to be lost, only thin.
 */
object CaptureTriage {

    /** The thin, still-provisional clusters that want tagging, in the order they were captured. */
    fun queue(notes: List<Note>): List<CaptureClusterer.ProvisionalSource> =
        CaptureClusterer.clusterNotes(notes).filter { it.isThin }

    /** How many notes across all thin clusters are waiting — the badge on the triage entry point. */
    fun pendingCount(notes: List<Note>): Int =
        queue(notes).sumOf { it.memberKeys.size }

    /** Whether a single capture would land in triage (best identifier only an app name / timestamp). */
    fun needsTriage(provenance: CaptureProvenance): Boolean = provenance.isThin
}
