package com.citation.core.sync

import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey

/**
 * Resolves an incoming **acquire intent** ("acquire *X* by *Y*") against the books Citation already
 * knows — the down half of the sync seam.
 *
 * A center-authored intent is *fuzzy* (title/author only, no identity), so this is the
 * **bind-or-create** checkpoint for the intent side: match it fuzzily to an existing record to avoid
 * spawning a duplicate wanted book, or create a fresh one. It reuses [BindOrCreate]'s matching but
 * differs in one crucial way — an intent must **never** flip a book to acquired. Receiving "acquire
 * X" only means the wish exists; whether you *have* it stays on the acquisition axis, owned by the
 * reader when it actually resolves an artifact. So a match is reported as [Outcome.AlreadyKnown]
 * (dedup, state untouched) and a miss as [Outcome.CreateWanted].
 */
object IntentReconciler {

    sealed interface Outcome {
        /** The intent matches a book we already track; do nothing but record the link. */
        data class AlreadyKnown(val key: EntityKey) : Outcome
        /** No match; create a new `wanted` book from the intent's fuzzy title/author. */
        data class CreateWanted(val title: String, val author: String?) : Outcome
    }

    /**
     * Reconcile [intent] against [candidates] (existing book records). Matches on fuzzy title+author
     * (identity-less, since an intent carries no ISBN/RR-id), never merging distinct editions.
     */
    fun reconcile(
        intent: AcquireBookIntent,
        candidates: List<BindOrCreate.Candidate>,
        fuzzyThreshold: Double = 0.82
    ): Outcome {
        val resolved = BindOrCreate.Resolved(
            identity = IdentitySet(emptyList()),
            title = intent.title,
            author = intent.author
        )
        return when (val decision = BindOrCreate.reconcile(resolved, candidates, fuzzyThreshold)) {
            is BindOrCreate.Decision.Bind -> Outcome.AlreadyKnown(decision.key)
            BindOrCreate.Decision.Create -> Outcome.CreateWanted(intent.title, intent.author)
        }
    }
}
