package com.citation.core.sync

import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentReconcilerTest {

    private fun candidate(key: String, title: String, author: String?, lifecycle: BookLifecycle = BookLifecycle.wanted()) =
        BindOrCreate.Candidate(EntityKey.parse(key)!!, IdentitySet(emptyList()), title, author, lifecycle)

    @Test
    fun fuzzyMatchIsAlreadyKnown() {
        val intent = AcquireBookIntent(
            EntityKey("LO", "Book", 42),
            "Designing Data Intensive Applications",
            "Martin Kleppmann"
        )
        val candidates = listOf(
            candidate("ER-Book-5", "Designing Data-Intensive Applications", "Kleppmann")
        )
        val outcome = IntentReconciler.reconcile(intent, candidates)
        assertTrue(outcome is IntentReconciler.Outcome.AlreadyKnown)
        assertEquals(EntityKey.parse("ER-Book-5"), (outcome as IntentReconciler.Outcome.AlreadyKnown).key)
    }

    @Test
    fun noMatchCreatesWanted() {
        val intent = AcquireBookIntent(EntityKey("LO", "Book", 1), "An Unrelated Work", "Someone Else")
        val outcome = IntentReconciler.reconcile(intent, listOf(candidate("ER-Book-1", "Cooking 101", "Chef")))
        assertEquals(IntentReconciler.Outcome.CreateWanted("An Unrelated Work", "Someone Else"), outcome)
    }

    @Test
    fun emptyLibraryCreatesWanted() {
        val intent = AcquireBookIntent(EntityKey("LO", "Book", 1), "Anything", null)
        assertEquals(
            IntentReconciler.Outcome.CreateWanted("Anything", null),
            IntentReconciler.reconcile(intent, emptyList())
        )
    }

    @Test
    fun anIntentNeverFlipsAcquisitionState() {
        // Even matching an already-acquired book, the outcome is a dedup link, not a state change.
        val acquired = candidate("ER-Book-9", "The Pragmatic Programmer", "Hunt", BookLifecycle.owned())
        val intent = AcquireBookIntent(EntityKey("LO", "Book", 3), "The Pragmatic Programmer", "Hunt")
        val outcome = IntentReconciler.reconcile(intent, listOf(acquired))
        assertTrue(outcome is IntentReconciler.Outcome.AlreadyKnown)
        // The candidate's own lifecycle is untouched by reconciliation (no state returned to mutate).
        assertEquals(AcquisitionState.ACQUIRED, acquired.lifecycle.acquisition)
    }
}
