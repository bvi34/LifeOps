package com.people.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonMergeTest {

    private val base = PersonPacket(personKey = "key-1", name = "Ellie", updatedAt = 1_000L)

    @Test
    fun `the newer record wins where it actually says something`() {
        val local = base.copy(phone = "555-0100", updatedAt = 1_000L)
        val incoming = base.copy(name = "Ellie Watts", email = "ellie@example.com", updatedAt = 2_000L)

        val merged = PersonMerge.merge(local, incoming).merged
        assertEquals("Ellie Watts", merged.name)
        assertEquals("ellie@example.com", merged.email)
        // The older record's phone survives: the newer one never mentioned it.
        assertEquals("555-0100", merged.phone)
        assertEquals(2_000L, merged.updatedAt)
    }

    @Test
    fun `a blank never beats a value, even from the newer side`() {
        // This is the whole reason the rule isn't record-level last-write-wins: LifeOps mints a
        // person from a calendar attendee (email, nothing else) after you filled in their details.
        val local = base.copy(birthDate = "2019-04-02", phone = "555-0100", updatedAt = 1_000L)
        val incoming = PersonPacket(
            personKey = "key-1",
            name = "Ellie",
            email = "ellie@example.com",
            birthDate = null,
            phone = "   ",
            updatedAt = 5_000L
        )

        val merged = PersonMerge.merge(local, incoming).merged
        assertEquals("2019-04-02", merged.birthDate)
        assertEquals("555-0100", merged.phone)
        assertEquals("ellie@example.com", merged.email)
    }

    @Test
    fun `an older record still fills in what the newer one lacks`() {
        val local = base.copy(email = "ellie@example.com", updatedAt = 9_000L)
        val incoming = base.copy(birthDate = "2019-04-02", updatedAt = 2_000L)

        val merged = PersonMerge.merge(local, incoming).merged
        assertEquals("ellie@example.com", merged.email)
        assertEquals("2019-04-02", merged.birthDate)
        assertEquals(9_000L, merged.updatedAt)
    }

    @Test
    fun `merging converges - both peers reach the same record from either direction`() {
        val a = base.copy(email = "ellie@example.com", updatedAt = 3_000L)
        val b = base.copy(birthDate = "2019-04-02", phone = "555-0100", updatedAt = 4_000L)

        val fromA = PersonMerge.merge(a, b).merged
        val fromB = PersonMerge.merge(b, a).merged
        // Identity is the local key on each side, so compare everything else.
        assertEquals(fromA.copy(personKey = ""), fromB.copy(personKey = ""))
    }

    @Test
    fun `two peers that bound by name converge on one key, from either direction`() {
        // Each side invented its own key for the same human before they ever met.
        val mine = base.copy(personKey = "zzz-local", updatedAt = 1_000L)
        val theirs = base.copy(personKey = "aaa-remote", updatedAt = 2_000L)

        assertEquals("aaa-remote", PersonMerge.merge(mine, theirs).merged.personKey)
        assertEquals("aaa-remote", PersonMerge.merge(theirs, mine).merged.personKey)
    }

    @Test
    fun `key convergence is stable once reached`() {
        // The failure this guards against is the obvious fix — "adopt the incoming key" — under
        // which both peers adopt the other's and swap places every round for ever.
        val settled = base.copy(personKey = "aaa-remote", updatedAt = 2_000L)
        val result = PersonMerge.merge(settled, settled.copy(updatedAt = 3_000L))
        assertEquals("aaa-remote", result.merged.personKey)
        assertFalse(PersonMerge.merge(result.merged, result.merged).changed)
    }

    @Test
    fun `archived is a state, so the newer record's answer stands`() {
        val local = base.copy(archived = true, updatedAt = 1_000L)
        val incoming = base.copy(archived = false, updatedAt = 2_000L)
        assertFalse(PersonMerge.merge(local, incoming).merged.archived)

        val staleUnarchive = base.copy(archived = false, updatedAt = 500L)
        assertTrue(PersonMerge.merge(local, staleUnarchive).merged.archived)
    }

    @Test
    fun `a delete archives rather than erasing`() {
        val local = base.copy(birthDate = "2019-04-02", updatedAt = 1_000L)
        val result = PersonMerge.merge(local, base.copy(deleted = true, updatedAt = 2_000L))

        assertTrue(result.changed)
        assertTrue(result.merged.archived)
        // The record itself survives — that is the point.
        assertEquals("2019-04-02", result.merged.birthDate)
        assertFalse(result.merged.deleted)
    }

    @Test
    fun `a stale delete never undoes a newer local edit`() {
        val local = base.copy(name = "Ellie Watts", updatedAt = 5_000L)
        val result = PersonMerge.merge(local, base.copy(deleted = true, updatedAt = 1_000L))
        assertFalse(result.merged.archived)
    }

    @Test
    fun `an identical packet reports no change, so nothing echoes back`() {
        val local = base.copy(email = "ellie@example.com", updatedAt = 4_000L)
        val result = PersonMerge.merge(local, local)
        assertFalse(result.changed)
    }

    @Test
    fun `a name is never blanked out by an empty one`() {
        val result = PersonMerge.merge(base, base.copy(name = "", updatedAt = 9_000L))
        assertEquals("Ellie", result.merged.name)
    }
}
