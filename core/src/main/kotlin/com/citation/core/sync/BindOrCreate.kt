package com.citation.core.sync

import com.citation.core.identity.DedupValidator
import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey

/**
 * **Bind-or-create reconciliation** — the single checkpoint where a fuzzy, center-authored "wanted"
 * book meets a concrete artifact the reader has resolved.
 *
 * LifeOps center authors a book knowing only a title/author ("acquire *Designing Data-Intensive
 * Applications* by Kleppmann"). Later the reader resolves an actual artifact — an EPUB with an ISBN,
 * say. Without a checkpoint you'd get *two* records: the wanted intent and the resolved book,
 * forever unlinked. Bind-or-create closes that: at resolution time, look for an existing record this
 * artifact should attach to (the pending intent, or an already-imported copy). If one is found,
 * **bind** to it (carry its key, upgrade its acquisition state); otherwise **create** fresh. One
 * decision point, so a book is never silently duplicated.
 */
object BindOrCreate {

    /** A candidate existing record to possibly bind to. */
    data class Candidate(
        val key: EntityKey,
        val identity: IdentitySet,
        val title: String,
        val author: String?,
        val lifecycle: BookLifecycle
    )

    /** The artifact the reader just resolved and wants to file. */
    data class Resolved(
        val identity: IdentitySet,
        val title: String,
        val author: String?
    )

    sealed interface Decision {
        /** Attach the resolved artifact to an existing record; its acquisition becomes ACQUIRED. */
        data class Bind(val key: EntityKey, val lifecycle: BookLifecycle) : Decision
        /** No match — mint a new record (caller assigns a fresh key). */
        data object Create : Decision
    }

    /**
     * Reconcile [resolved] against existing [candidates].
     *
     * Matching prefers **hard identity** (an ISBN/RR-id/file-hash that [DedupValidator] calls the
     * same work) and falls back to **fuzzy title+author** for the common case where the intent is
     * still identity-less (center only had a title). The first candidate that matches by identity
     * wins outright; otherwise the best fuzzy match above [fuzzyThreshold] is bound. On a bind, the
     * candidate's acquisition axis is advanced to ACQUIRED (its reading axis is left alone).
     */
    fun reconcile(
        resolved: Resolved,
        candidates: List<Candidate>,
        fuzzyThreshold: Double = 0.82
    ): Decision {
        // 1) Hard identity match — decisive.
        candidates.firstOrNull {
            DedupValidator.compare(resolved.identity, it.identity) == DedupValidator.Verdict.SAME
        }?.let { return bind(it) }

        // 2) Never bind to something identity says is a *different* work (distinct ISBN/edition).
        val eligible = candidates.filter {
            DedupValidator.compare(resolved.identity, it.identity) != DedupValidator.Verdict.DIFFERENT
        }

        // 3) Fuzzy title+author for still-fuzzy intents.
        val best = eligible
            .map { it to fuzzyScore(resolved, it) }
            .filter { it.second >= fuzzyThreshold }
            .maxByOrNull { it.second }
            ?.first

        return best?.let { bind(it) } ?: Decision.Create
    }

    private fun bind(candidate: Candidate): Decision.Bind {
        val advanced = when (candidate.lifecycle.acquisition) {
            AcquisitionState.WANTED, AcquisitionState.RESOLVING, AcquisitionState.UNAVAILABLE ->
                candidate.lifecycle.copy(acquisition = AcquisitionState.ACQUIRED)
            AcquisitionState.ACQUIRED -> candidate.lifecycle
        }
        return Decision.Bind(candidate.key, advanced)
    }

    private fun fuzzyScore(resolved: Resolved, candidate: Candidate): Double {
        val titleScore = tokenSimilarity(resolved.title, candidate.title)
        val authorScore = when {
            resolved.author.isNullOrBlank() || candidate.author.isNullOrBlank() -> null
            else -> tokenSimilarity(resolved.author, candidate.author)
        }
        // Title carries the decision; author, when both present, nudges it.
        return if (authorScore == null) titleScore else 0.7 * titleScore + 0.3 * authorScore
    }

    /** Jaccard token overlap on normalised words — robust to word order and minor title variance. */
    private fun tokenSimilarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return inter / union
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
}
