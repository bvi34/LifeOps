package com.people.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The seam with all three peers on it at once, driven over the real transport.
 *
 * The engine tests exercise one `applyInbound` at a time and the two-peer test hands envelopes
 * between two engines directly. Neither catches what goes wrong when People, LifeOps **and** Health
 * are all reconciling in the same folder: whose envelope a peer is allowed to prune, whether the
 * bind-only peer's refusal to create takes the rest of the round down with it, and whether three
 * independently-invented keys for the same person actually converge on one.
 *
 * [Peer] below is the part of each app's sync service that isn't Room — the version stamping, the
 * per-peer cursors, and the "publish above the lowest ack" rule — so a round here is the same shape
 * as a round in `PeopleSyncService`, `PeopleSyncRepository` and `HealthSyncService`.
 */
class ThreePeerRoundTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: PeopleEnvelopeStore
    private lateinit var people: Peer
    private lateinit var lifeops: Peer
    private lateinit var health: Peer

    @Before
    fun setUp() {
        store = PeopleEnvelopeStore(folder.newFolder("people-sync"))
        people = Peer(Peers.PEOPLE, store, listOf(Peers.LIFEOPS, Peers.HEALTH))
        lifeops = Peer(Peers.LIFEOPS, store, listOf(Peers.PEOPLE, Peers.HEALTH))
        health = Peer(
            Peers.HEALTH,
            store,
            listOf(Peers.PEOPLE, Peers.LIFEOPS),
            createUnknown = false,
            refuseCreate = true
        )
    }

    @Test
    fun `a person added in People reaches LifeOps, and Health leaves them alone`() {
        people.edit("p-1", PersonPacket("k-ellie", "Ellie", birthDate = "2019-04-02", updatedAt = 1_000L))

        people.round()
        lifeops.round()
        health.round()

        assertEquals("2019-04-02", lifeops.roster.byName("Ellie")?.birthDate)
        // Health annotates people; it does not grow a medical profile for one nobody is tracking.
        assertTrue(health.roster.rows.isEmpty())
    }

    @Test
    fun `a person added in LifeOps reaches People without waiting for a second round`() {
        lifeops.edit("l-1", PersonPacket("k-rob", "Rob", email = "rob@example.com", updatedAt = 1_000L))

        lifeops.round()
        people.round()

        assertEquals("rob@example.com", people.roster.byName("Rob")?.email)
    }

    @Test
    fun `Health hears a birth date from People and publishes its own profile onward`() {
        // Health is told about the child directly — the deliberate act the bind-only policy expects.
        health.edit("h-1", PersonPacket("k-health-ellie", "Ellie", updatedAt = 500L))
        health.round()

        people.edit("p-1", PersonPacket("k-people-ellie", "Ellie", birthDate = "2019-04-02", updatedAt = 1_000L))
        people.round()
        lifeops.round()
        health.round()

        // The birth date is the whole reason Health is on the seam: the fever rules are age-aware.
        assertEquals("2019-04-02", health.roster.byName("Ellie")?.birthDate)
        // And Health's own profile reached the household directory rather than staying local.
        assertNotNull(lifeops.roster.byName("Ellie"))
        assertEquals(1, people.roster.rows.size)
    }

    @Test
    fun `three peers holding the same person under three keys converge on one`() {
        people.edit("p-1", PersonPacket("mmm-people", "Ellie", updatedAt = 1_000L))
        lifeops.edit("l-1", PersonPacket("aaa-lifeops", "Ellie", updatedAt = 1_100L))
        health.edit("h-1", PersonPacket("zzz-health", "Ellie", updatedAt = 1_200L))

        // Two full passes: one to exchange, one to take what the exchange produced.
        repeat(2) {
            people.round()
            lifeops.round()
            health.round()
        }

        val keys = setOf(
            people.roster.rows.values.single().personKey,
            lifeops.roster.rows.values.single().personKey,
            health.roster.rows.values.single().personKey
        )
        // The lower key wins everywhere, so a later rename still binds instead of duplicating.
        assertEquals(setOf("aaa-lifeops"), keys)
    }

    @Test
    fun `a withdrawal from LifeOps archives rather than erases, everywhere`() {
        people.edit("p-1", PersonPacket("k-ellie", "Ellie", updatedAt = 1_000L))
        people.round()
        lifeops.round()

        lifeops.edit(
            "l-tombstone",
            PersonPacket("k-ellie", "Ellie", deleted = true, updatedAt = 2_000L)
        )
        lifeops.round()
        people.round()

        val ellie = people.roster.rows.values.single()
        assertTrue(ellie.archived)
        assertEquals(1, people.roster.rows.size)
    }

    @Test
    fun `a bind-only peer whose row vanished mid-round skips it instead of failing the round`() {
        // Health tracks Ellie, and the profile is deleted between the binder matching it and the
        // merge reading it back. Re-creating it would resurrect the profile the user just removed;
        // throwing would take the rest of the envelope down with it.
        health.edit("h-1", PersonPacket("k-ellie", "Ellie", updatedAt = 1_000L))
        health.roster.vanish("h-1")

        people.edit("p-1", PersonPacket("k-ellie", "Ellie", birthDate = "2019-04-02", updatedAt = 2_000L))
        people.edit("p-2", PersonPacket("k-rob", "Rob", updatedAt = 2_000L))
        people.round()

        health.round()

        // Nothing was invented, and the packet behind the vanished one was still taken.
        assertNull(health.roster.byName("Rob"))
        assertEquals(2L, health.cursorFor(Peers.PEOPLE))
    }

    @Test
    fun `a peer that has been away still receives what it missed`() {
        people.edit("p-1", PersonPacket("k-ellie", "Ellie", updatedAt = 1_000L))
        people.round()
        // LifeOps keeps up; Health never opens.
        lifeops.round()
        people.round()

        people.edit("p-2", PersonPacket("k-rob", "Rob", updatedAt = 2_000L))
        people.round()
        lifeops.round()
        people.round()

        // Health finally opens. Publishing above the *lowest* ack is what leaves both people in
        // People's envelope rather than only the one LifeOps hadn't yet seen.
        health.edit("h-1", PersonPacket("k-health-ellie", "Ellie", updatedAt = 500L))
        health.round()

        assertEquals(2L, health.cursorFor(Peers.PEOPLE))
        assertEquals("k-ellie", health.roster.byName("Ellie")?.personKey)
    }
}

/**
 * One peer's non-Room half: version stamping, per-peer cursors, and the publish-above-the-lowest-ack
 * rule the three real sync services share.
 */
private class Peer(
    private val name: String,
    private val store: PeopleEnvelopeStore,
    private val peers: List<String>,
    createUnknown: Boolean = true,
    refuseCreate: Boolean = false
) {

    val roster = FakeRoster(name, refuseCreate)

    private val engine = PeopleSyncEngine(name, roster, createUnknown)
    private val cursors = HashMap<String, Long>()

    /** localId -> the version stamped when this peer last edited that row *locally*. */
    private val versions = LinkedHashMap<String, Long>()
    private var nextVersion = 0L

    fun cursorFor(peer: String): Long = cursors[peer] ?: 0L

    /**
     * A local edit: writes the row and stamps a new outgoing version, exactly as each repository's
     * `syncVersion = maxSyncVersion() + 1` does. Rows that arrive over the seam are written by the
     * engine and get no entry here, which is what stops a merge echoing back for ever.
     */
    fun edit(localId: String, packet: PersonPacket) {
        roster.put(packet, localId)
        versions[localId] = ++nextVersion
    }

    /** One round: take every other peer's envelope, then publish our own. */
    fun round() {
        for (other in peers) {
            val envelope = store.read(other) ?: continue
            val cursor = cursorFor(other)
            val applied = engine.applyInbound(envelope, cursor)
            if (applied.ackedThrough > cursor) cursors[other] = applied.ackedThrough
        }

        val floor = peers.minOfOrNull { store.read(it)?.ackFor(name) ?: 0L } ?: 0L
        val changes = versions
            .filterValues { it > floor }
            .mapNotNull { (localId, version) ->
                roster.read(localId)?.let { VersionedPacket(version, it) }
            }
        store.write(engine.buildOutbound(changes, peers.associateWith { cursorFor(it) }))
    }
}
