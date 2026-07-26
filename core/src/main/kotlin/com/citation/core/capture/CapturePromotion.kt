package com.citation.core.capture

import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey
import com.citation.core.sync.BindOrCreate
import com.citation.core.sync.BookLifecycle

/**
 * **Promotion** — the moment a provisional capture cluster meets a real book record and binds to it,
 * instead of being stranded.
 *
 * You saved five highlights from a Kindle book, or three quotes from an article, long before Citation
 * held the book itself. They clustered under a provisional source (a `book:…` identity, or just a
 * URL). Later the book arrives properly — you import the EPUB, it carries an ISBN. Promotion is the
 * checkpoint that says: this concrete record *is* what those captures were about, so **bind** them to
 * it rather than leaving them orphaned.
 *
 * This is deliberately the **same fuzzy-to-concrete lifecycle as center-authored book intents** — it
 * reuses [BindOrCreate.reconcile], so the exact same matching rules apply: a hard identity match
 * (ISBN/RR-id) is decisive, a fuzzy title+author match carries the identity-less case, and distinct
 * editions never collapse. Only the framing is inverted: the *concrete record* is the artifact being
 * filed, and each *provisional cluster* is a candidate it might already belong to.
 */
object CapturePromotion {

    /** The concrete book record that just became real and might claim some provisional clusters. */
    data class PromotableRecord(
        val bookKey: EntityKey,
        val identity: IdentitySet,
        val title: String,
        val author: String?
    )

    /** A resolved binding: this cluster (and its member notes) belongs to this book. */
    data class Promotion(
        val clusterId: String,
        val bookKey: EntityKey,
        val memberKeys: List<EntityKey>
    )

    /**
     * Decide which of [clusters] the newly-concrete [record] should claim. Already-bound clusters are
     * skipped (promotion never re-binds); each still-provisional cluster is reconciled against the
     * record via [BindOrCreate], and a [BindOrCreate.Decision.Bind] yields a [Promotion].
     *
     * The returned promotions tell the caller exactly which notes to re-point at [record]'s book key.
     */
    fun promote(
        record: PromotableRecord,
        clusters: List<CaptureClusterer.ProvisionalSource>,
        fuzzyThreshold: Double = 0.82
    ): List<Promotion> {
        // The record framed as the artifact being filed; each cluster is a candidate it may match.
        val recordAsResolved = BindOrCreate.Resolved(
            identity = record.identity,
            title = record.title,
            author = record.author
        )
        return clusters
            .filter { it.isProvisional }
            .mapNotNull { cluster ->
                val candidate = BindOrCreate.Candidate(
                    key = record.bookKey,
                    identity = cluster.bookIdentity()?.let { IdentitySet(it) } ?: IdentitySet(emptyList()),
                    title = cluster.displayTitle,
                    author = null,
                    lifecycle = BookLifecycle.owned()
                )
                when (BindOrCreate.reconcile(recordAsResolved, listOf(candidate), fuzzyThreshold)) {
                    is BindOrCreate.Decision.Bind ->
                        Promotion(cluster.clusterId, record.bookKey, cluster.memberKeys)
                    BindOrCreate.Decision.Create -> null
                }
            }
    }

    /** The hard book identity a cluster's id encodes, if it is a `book:…` cluster. */
    private fun CaptureClusterer.ProvisionalSource.bookIdentity() = ClusterId.bookIdentityOf(clusterId)
}
