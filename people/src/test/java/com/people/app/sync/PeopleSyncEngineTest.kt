package com.people.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleSyncEngineTest {

    private fun engine(roster: FakeRoster, peer: String = Peers.PEOPLE) = PeopleSyncEngine(peer, roster)

    private fun envelope(peer: String, vararg packets: Pair<Long, PersonPacket>) =
        PeerEnvelope(peer, packets.map { VersionedPacket(it.first, it.second) })

    @Test
    fun `an unseen person is created`() {
        val roster = FakeRoster("p")
        val applied = engine(roster).applyInbound(
            envelope(Peers.LIFEOPS, 1L to PersonPacket("key-1", "Ellie", updatedAt = 1_000L)),
            sinceVersion = 0L
        )

        assertEquals(1, applied.created)
        assertEquals(1L, applied.ackedThrough)
        assertEquals("Ellie", roster.rows.values.single().name)
    }

    @Test
    fun `re-applying the same envelope changes nothing`() {
        val roster = FakeRoster("p")
        val engine = engine(roster)
        val incoming = envelope(Peers.LIFEOPS, 1L to PersonPacket("key-1", "Ellie", updatedAt = 1_000L))

        val first = engine.applyInbound(incoming, sinceVersion = 0L)
        val second = engine.applyInbound(incoming, sinceVersion = first.ackedThrough)

        assertEquals(1, first.created)
        assertEquals(0, second.created)
        assertEquals(0, second.updated)
        assertEquals(1, roster.rows.size)
    }

    @Test
    fun `packets at or below the cursor are ignored`() {
        val roster = FakeRoster("p")
        val applied = engine(roster).applyInbound(
            envelope(
                Peers.LIFEOPS,
                1L to PersonPacket("key-1", "Ellie", updatedAt = 1_000L),
                2L to PersonPacket("key-2", "Marta", updatedAt = 1_000L),
                3L to PersonPacket("key-3", "Rob", updatedAt = 1_000L)
            ),
            sinceVersion = 2L
        )

        assertEquals(1, applied.created)
        assertEquals(3L, applied.ackedThrough)
        assertEquals(listOf("Rob"), roster.rows.values.map { it.name })
    }

    @Test
    fun `the cursor advances past packets that merged to nothing`() {
        // Otherwise a packet we have genuinely taken, but that changed nothing, would be resent
        // and re-taken every round for ever.
        val roster = FakeRoster("p")
        val packet = PersonPacket("key-1", "Ellie", updatedAt = 1_000L)
        roster.put(packet)

        val applied = engine(roster).applyInbound(envelope(Peers.LIFEOPS, 7L to packet), sinceVersion = 0L)
        assertEquals(0, applied.updated)
        assertEquals(1, applied.unchanged)
        assertEquals(7L, applied.ackedThrough)
    }

    @Test
    fun `an arriving packet binds to the person we already had by email`() {
        val roster = FakeRoster("p")
        roster.put(PersonPacket("people-key", "Rob", email = "rob@example.com", updatedAt = 1_000L))

        val applied = engine(roster).applyInbound(
            envelope(
                Peers.LIFEOPS,
                1L to PersonPacket("lifeops-key", "Robert", email = "ROB@example.com", phone = "555-0100", updatedAt = 2_000L)
            ),
            sinceVersion = 0L
        )

        assertEquals(0, applied.created)
        assertEquals(1, applied.updated)
        assertEquals(1, roster.rows.size)
        val merged = roster.rows.values.single()
        assertEquals("Robert", merged.name)
        assertEquals("555-0100", merged.phone)
        // Two invented keys for one human converge on the lower of them, deterministically, so both
        // peers land on the same one instead of each keeping its own and relying on the name
        // continuing to match for ever.
        assertEquals("lifeops-key", merged.personKey)
    }

    @Test
    fun `a tombstone for somebody we never had creates nobody`() {
        val roster = FakeRoster("p")
        val applied = engine(roster).applyInbound(
            envelope(Peers.LIFEOPS, 1L to PersonPacket("key-1", "Ghost", deleted = true, updatedAt = 1_000L)),
            sinceVersion = 0L
        )

        assertEquals(0, applied.created)
        assertTrue(roster.rows.isEmpty())
        assertEquals(1L, applied.ackedThrough)
    }

    @Test
    fun `a bind-only peer keeps up with its own people and ignores the rest`() {
        // Health tracks one child. The household has four people; three of them are nobody Health
        // has any business growing a medical profile for.
        val health = FakeRoster("health")
        health.put(PersonPacket("key-ellie", "Ellie", updatedAt = 1_000L))
        val engine = PeopleSyncEngine(Peers.HEALTH, health, createUnknown = false)

        val applied = engine.applyInbound(
            envelope(
                Peers.PEOPLE,
                1L to PersonPacket("key-ellie", "Ellie", birthDate = "2019-04-02", updatedAt = 2_000L),
                2L to PersonPacket("key-rob", "Rob", updatedAt = 2_000L),
                3L to PersonPacket("key-marta", "Marta", updatedAt = 2_000L)
            ),
            sinceVersion = 0L
        )

        assertEquals(0, applied.created)
        assertEquals(1, applied.updated)
        assertEquals(2, applied.unchanged)
        assertEquals(1, health.rows.size)
        // The person it does track gained the birth date — which is the whole point for Health.
        assertEquals("2019-04-02", health.byName("Ellie")!!.birthDate)
        // And the cursor still cleared all three, so the skipped two don't replay for ever.
        assertEquals(3L, applied.ackedThrough)
    }

    @Test
    fun `a bind-only peer still accepts a withdrawal for somebody it tracks`() {
        val health = FakeRoster("health")
        health.put(PersonPacket("key-ellie", "Ellie", updatedAt = 1_000L))
        val engine = PeopleSyncEngine(Peers.HEALTH, health, createUnknown = false)

        engine.applyInbound(
            envelope(Peers.PEOPLE, 1L to PersonPacket("key-ellie", "Ellie", deleted = true, updatedAt = 2_000L)),
            sinceVersion = 0L
        )

        // Archived, not erased: the readings recorded against her are still there.
        assertTrue(health.rows.values.single().archived)
    }

    @Test
    fun `outbound carries the changes in version order with our ack cursors`() {
        val envelope = engine(FakeRoster("p")).buildOutbound(
            changes = listOf(
                VersionedPacket(3L, PersonPacket("key-3", "Rob")),
                VersionedPacket(1L, PersonPacket("key-1", "Ellie"))
            ),
            acks = mapOf(Peers.LIFEOPS to 12L)
        )

        assertEquals(listOf(1L, 3L), envelope.packets.map { it.version })
        assertEquals(12L, envelope.ackFor(Peers.LIFEOPS))
        assertEquals(0L, envelope.ackFor(Peers.HEALTH))
    }

    @Test
    fun `binding by name survives a later rename, because the key converged`() {
        // Round one: two peers holding the same person under their own invented keys, bound by name.
        val people = FakeRoster("people")
        people.put(PersonPacket("zzz-people", "Ellie", updatedAt = 1_000L))
        val engine = PeopleSyncEngine(Peers.PEOPLE, people)

        engine.applyInbound(
            envelope(Peers.LIFEOPS, 1L to PersonPacket("aaa-lifeops", "Ellie", updatedAt = 2_000L)),
            sinceVersion = 0L
        )
        assertEquals("aaa-lifeops", people.rows.values.single().personKey)

        // Round two: the other peer renames her. Nothing binds by name any more — the key has to
        // carry it, which is exactly what round one was for.
        val applied = engine.applyInbound(
            envelope(Peers.LIFEOPS, 2L to PersonPacket("aaa-lifeops", "Ellie Watts", updatedAt = 3_000L)),
            sinceVersion = 1L
        )

        assertEquals(0, applied.created)
        assertEquals(1, applied.updated)
        assertEquals(1, people.rows.size)
        assertEquals("Ellie Watts", people.rows.values.single().name)
    }

    @Test
    fun `a full two-peer round converges on one roster each`() {
        // People knows Ellie (with a birth date); LifeOps knows the same Ellie from a calendar
        // attendee (email only) plus a Rob nobody else has seen.
        val people = FakeRoster("people")
        val lifeops = FakeRoster("lifeops")
        people.put(PersonPacket("people-ellie", "Ellie", birthDate = "2019-04-02", updatedAt = 1_000L))
        lifeops.put(PersonPacket("lifeops-ellie", "ellie", email = "ellie@example.com", updatedAt = 2_000L))
        lifeops.put(PersonPacket("lifeops-rob", "Rob", email = "rob@example.com", updatedAt = 2_000L))

        val peopleEngine = PeopleSyncEngine(Peers.PEOPLE, people)
        val lifeopsEngine = PeopleSyncEngine(Peers.LIFEOPS, lifeops)

        val fromLifeOps = lifeopsEngine.buildOutbound(
            lifeops.rows.values.mapIndexed { index, packet -> VersionedPacket(index + 1L, packet) },
            acks = emptyMap()
        )
        val intoPeople = peopleEngine.applyInbound(fromLifeOps, sinceVersion = 0L)

        // Ellie bound by name (neither side's email contradicted it); Rob is new.
        assertEquals(1, intoPeople.updated)
        assertEquals(1, intoPeople.created)
        assertEquals(2, people.rows.size)
        val ellie = people.byName("ellie")!!
        assertEquals("2019-04-02", ellie.birthDate)
        assertEquals("ellie@example.com", ellie.email)

        // Now People publishes its merged view back, and LifeOps takes it.
        val fromPeople = peopleEngine.buildOutbound(
            people.rows.values.mapIndexed { index, packet -> VersionedPacket(index + 1L, packet) },
            acks = mapOf(Peers.LIFEOPS to intoPeople.ackedThrough)
        )
        val intoLifeOps = lifeopsEngine.applyInbound(fromPeople, sinceVersion = 0L)

        assertEquals(0, intoLifeOps.created)
        assertEquals(2, lifeops.rows.size)
        // LifeOps has gained the birth date it never had, without losing its email.
        val lifeopsEllie = lifeops.rows.values.first { it.email == "ellie@example.com" }
        assertEquals("2019-04-02", lifeopsEllie.birthDate)
        assertEquals(intoPeople.ackedThrough, fromPeople.ackFor(Peers.LIFEOPS))
    }
}
