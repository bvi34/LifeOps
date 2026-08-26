package com.lifeops.app.data.repository

import com.lifeops.app.util.toPacket
import com.people.app.sync.PeerRoster
import com.people.app.sync.Peers
import com.people.app.sync.PeopleEnvelopeStore
import com.people.app.sync.PeopleSyncEngine
import com.people.app.sync.PersonBinder
import com.people.app.sync.PersonPacket
import com.people.app.sync.VersionedPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The LifeOps side of the People ↔ LifeOps sync seam — the counterpart to [CitationSyncRepository],
 * and built the same way for the same reasons.
 *
 * Where the Citation seam is asymmetric (Citation sends telemetry up, LifeOps sends acquire-intents
 * down), this one is symmetric: both peers hold a roster, both can edit it, and both publish the
 * same kind of packet. So both ends run the *same* engine and the *same* merge rule out of
 * `:people`, rather than each end implementing half a protocol and slowly disagreeing with the other
 * about what a conflict means.
 *
 * LifeOps keeps owning its `persons` table and every foreign key into it. Nothing here reads
 * People's database or hands it LifeOps rows; the two exchange envelopes in a shared folder and each
 * decides for itself what to store.
 *
 * The cursor accessors are plain lambdas (not a repository handle) so a round can be driven in a
 * test against a temp folder, exactly like the `:core` transport tests.
 */
class PeopleSyncRepository(
    private val personRepository: PersonRepository,
    private val syncDir: File,
    private val readCursor: (String) -> Long,
    private val writeCursor: (String, Long) -> Unit
) {

    /** Outcome of one round, for status/logging. */
    data class Summary(
        val peersSeen: List<String>,
        val created: Int,
        val updated: Int,
        val published: Int
    )

    /**
     * LifeOps' roster as the engine sees it.
     *
     * The suspend/blocking bridge is confined to this one class so [PeerRoster] can stay a pure
     * interface — which is what lets the reconciliation be JVM-tested without Room. [sync] already
     * runs on the IO dispatcher, so nothing on a main thread ever waits here.
     */
    private inner class LifeOpsRoster : PeerRoster {

        /**
         * Snapshot the roster rather than re-reading it per packet, and drop the snapshot on any
         * write that invalidates it — a create adds a row to bind against, and a merge can move a
         * row's `personKey` (identity converges on the lower of the two, see `PersonMerge`). The
         * second case matters now that both other peers land in the same round: without it, the
         * packet from the second peer binds against the key the row held before the first moved it.
         */
        private var cached: List<PersonBinder.Candidate>? = null

        override fun candidates(): List<PersonBinder.Candidate> =
            cached ?: runBlocking { personRepository.bindingCandidates() }.also { cached = it }

        override fun read(localId: String): PersonPacket? = runBlocking {
            personRepository.entityById(localId)?.toPacket()
        }

        override fun update(localId: String, packet: PersonPacket) = runBlocking {
            personRepository.applyMerged(localId, packet)
            cached = null
        }

        override fun create(packet: PersonPacket): String = runBlocking {
            personRepository.createFromPacket(packet).also { cached = null }
        }
    }

    /** Serializes rounds — see the note on [sync]. */
    private val roundLock = Mutex()

    /**
     * Run one round: take each peer's envelope from the shared folder, fold in everything above our
     * cursor for that peer, then publish our own changes with the acks that let them prune.
     *
     * Idempotent — re-running it changes nothing — so it is safe to call as often as
     * [com.lifeops.app.LifeOpsApp.syncPeople] does: on launch, on every LifeOps foreground, and
     * after every local person edit.
     *
     * Idempotence makes a *repeated* round free, but it says nothing about two *overlapping* ones:
     * each snapshots the roster once and then binds every packet against that snapshot, so two
     * racing rounds would both decide the same arriving person is unknown and both create a row for
     * them. So rounds are serialized rather than merely idempotent — with a foreground round and an
     * edit round now able to land together, that overlap is ordinary rather than exotic.
     */
    suspend fun sync(
        peers: List<String> = DEFAULT_PEERS,
        /**
         * Rewind every cursor first, so the round re-reads the other peers' rosters in full. Set
         * when LifeOps has just gained a person of its own — the packet that would bind them to the
         * household record is behind the cursor and would otherwise never be seen again.
         */
        rescan: Boolean = false
    ): Summary = withContext(Dispatchers.IO) {
        roundLock.withLock { round(peers, rescan) }
    }

    private suspend fun round(peers: List<String>, rescan: Boolean): Summary {
        if (rescan) peers.forEach { writeCursor(it, 0L) }
        val store = PeopleEnvelopeStore(syncDir)
        val engine = PeopleSyncEngine(Peers.LIFEOPS, LifeOpsRoster())

        var created = 0
        var updated = 0
        val seen = ArrayList<String>()

        for (peer in peers) {
            val incoming = store.read(peer) ?: continue
            seen += peer
            val cursor = readCursor(peer)
            val applied = engine.applyInbound(incoming, cursor)
            created += applied.created
            updated += applied.updated
            // Persist the cursor even when nothing changed: those packets were still taken, and a
            // cursor left behind them replays the whole round next launch.
            if (applied.ackedThrough > cursor) writeCursor(peer, applied.ackedThrough)
        }

        // Publish the whole roster, not a delta above what the peers have acknowledged. Pruning to
        // the lowest ack meant a peer could only bind a person while that person's packet was still
        // on the wire, and once every peer had acked it, it was gone for good — so a peer that later
        // changed what it holds had no way back to that person's record. A household is a handful of
        // rows; the cursors still suppress re-application, so a settled round is still a no-op.
        //
        // It is also how the household LifeOps already knows about reaches a freshly-installed
        // People without an import step, which the old floor-of-zero special case only managed on a
        // first run.
        val changes = personRepository.changesSince(0L).map { (version, packet) ->
            VersionedPacket(version, packet)
        }
        store.write(
            engine.buildOutbound(changes = changes, acks = peers.associateWith { readCursor(it) })
        )

        return Summary(peersSeen = seen, created = created, updated = updated, published = changes.size)
    }

    companion object {
        /**
         * Who LifeOps reconciles with. People holds the household outright; Health is on the seam as
         * a bind-only peer, so LifeOps hears about a person Health tracks but Health never grows a
         * profile for one it doesn't.
         */
        val DEFAULT_PEERS = listOf(Peers.PEOPLE, Peers.HEALTH)
    }
}
