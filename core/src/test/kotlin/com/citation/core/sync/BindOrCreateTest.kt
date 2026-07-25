package com.citation.core.sync

import com.citation.core.identity.IdentityKey
import com.citation.core.identity.IdentitySet
import com.citation.core.key.EntityKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BindOrCreateTest {

    private fun wantedCandidate(
        key: String,
        title: String,
        author: String?,
        identity: IdentitySet = IdentitySet(emptyList())
    ) = BindOrCreate.Candidate(
        key = EntityKey.parse(key)!!,
        identity = identity,
        title = title,
        author = author,
        lifecycle = BookLifecycle.wanted()
    )

    @Test
    fun bindsFuzzyCenterAuthoredIntentByTitle() {
        // Center authored "wanted" with only a title/author; reader resolves an EPUB.
        val candidate = wantedCandidate("LO-Book-42", "Designing Data-Intensive Applications", "Kleppmann")
        val resolved = BindOrCreate.Resolved(
            identity = IdentitySet(IdentityKey.Isbn("9781449373320")),
            title = "Designing Data Intensive Applications",
            author = "Martin Kleppmann"
        )
        val decision = BindOrCreate.reconcile(resolved, listOf(candidate))
        assertTrue(decision is BindOrCreate.Decision.Bind)
        decision as BindOrCreate.Decision.Bind
        assertEquals(EntityKey.parse("LO-Book-42"), decision.key)
        // Binding advances acquisition to ACQUIRED, leaving reading untouched.
        assertEquals(AcquisitionState.ACQUIRED, decision.lifecycle.acquisition)
        assertEquals(ReadingState.TO_READ, decision.lifecycle.reading)
    }

    @Test
    fun bindsByHardIdentityEvenWhenTitlesDiffer() {
        val candidate = wantedCandidate(
            "LO-Book-7", "DDIA", "MK",
            identity = IdentitySet(IdentityKey.Isbn("9781449373320"))
        )
        val resolved = BindOrCreate.Resolved(
            identity = IdentitySet(IdentityKey.Isbn("978-1449373320")),
            title = "totally unrelated title string",
            author = null
        )
        val decision = BindOrCreate.reconcile(resolved, listOf(candidate))
        assertTrue(decision is BindOrCreate.Decision.Bind)
    }

    @Test
    fun differentEditionCreatesRatherThanBinds() {
        // A 2nd-edition ISBN must never bind to the 1st-edition record.
        val firstEd = wantedCandidate(
            "LO-Book-9", "Designing Data-Intensive Applications", "Kleppmann",
            identity = IdentitySet(IdentityKey.Isbn("9781449373320"))
        )
        val secondEd = BindOrCreate.Resolved(
            identity = IdentitySet(IdentityKey.Isbn("9781098119003")),
            title = "Designing Data-Intensive Applications",
            author = "Kleppmann"
        )
        assertEquals(BindOrCreate.Decision.Create, BindOrCreate.reconcile(secondEd, listOf(firstEd)))
    }

    @Test
    fun noMatchCreates() {
        val candidate = wantedCandidate("LO-Book-1", "Some Other Book", "Nobody")
        val resolved = BindOrCreate.Resolved(IdentitySet(emptyList()), "An Unrelated Work", "Someone")
        assertEquals(BindOrCreate.Decision.Create, BindOrCreate.reconcile(resolved, listOf(candidate)))
    }

    @Test
    fun emptyCandidatesCreates() {
        val resolved = BindOrCreate.Resolved(IdentitySet(emptyList()), "Anything", null)
        assertEquals(BindOrCreate.Decision.Create, BindOrCreate.reconcile(resolved, emptyList()))
    }
}
