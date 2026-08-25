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
        // The local key stands once bound, so the two peers don't trade keys back and forth.
        assertEquals("people-key", merged.personKey)
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
