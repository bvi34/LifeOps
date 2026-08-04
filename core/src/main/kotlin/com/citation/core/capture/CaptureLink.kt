package com.citation.core.capture

import com.citation.core.key.EntityKey
import com.citation.core.note.Note

/**
 * **Manual linking** — the user-driven counterpart to [CapturePromotion].
 *
 * Promotion binds provisional captures to a book *automatically*, the moment that book arrives with a
 * matching hard identity (you import the EPUB, it carries the ISBN). But often you already hold the
 * source and just want to say so: "these three quotes I saved from a browser are from *this* book on my
 * shelf." That's this. There is **no fuzzy matching** — the user has decided which book — so a link is a
 * direct re-point, not a reconciliation.
 *
 * A link operates at **cluster** granularity, exactly like promotion: captures that share a frozen
 * cluster id are one provisional source, so binding that source binds all of them together (link one
 * highlight from a Kindle-notebook export and the whole export follows). Already-bound members and notes
 * from other clusters are left untouched, and — as everywhere in capture — the frozen snapshot, title,
 * and cluster id are preserved; only the `bookKey` is filled in (via [CaptureBuilder.bind]), so the note
 * stays legible and the cluster view stays intact, now reporting a binding.
 */
object CaptureLink {

    /**
     * Re-point every still-provisional capture in [clusterId] at [bookKey], returning just those
     * rebound notes for the caller to persist + re-post. [notes] is the full capture corpus; members of
     * other clusters, and members already bound, are filtered out (linking never re-binds).
     */
    fun link(notes: List<Note>, clusterId: String, bookKey: EntityKey?): List<Note> =
        notes
            .filter { it.source.sourceId == clusterId && it.source.bookKey == null }
            .map { CaptureBuilder.bind(it, bookKey) }
}
