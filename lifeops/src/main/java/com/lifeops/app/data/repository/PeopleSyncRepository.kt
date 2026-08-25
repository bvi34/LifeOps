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

        /** Snapshot once per round rather than per packet; it cannot change mid-round. */
        private var cached: List<PersonBinder.Candidate>? = null

        override fun candidates(): List<PersonBinder.Candidate> =
            cached ?: runBlocking { personRepository.bindingCandidates() }.also { cached = it }

        override fun read(localId: String): PersonPacket? = runBlocking {
            personRepository.entityById(localId)?.toPacket()
        }

        override fun update(localId: String, packet: PersonPacket) = runBlocking {
            personRepository.applyMerged(localId, packet)
        }

        override fun create(packet: PersonPacket): String = runBlocking {
            personRepository.createFromPacket(packet).also { cached = null }
        }
    }

    /**
     * Run one round: take each peer's envelope from the shared folder, fold in everything above our
     * cursor for that peer, then publish our own changes with the acks that let them prune.
     *
     * Idempotent — re-running it changes nothing — so it is safe on every app launch, which is where
     * [com.lifeops.app.LifeOpsApp] calls it from.
     */
    suspend fun sync(peers: List<String> = DEFAULT_PEERS): Summary = withContext(Dispatchers.IO) {
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

        // Publish above the *lowest* ack across peers, so one that has been away still receives what
        // it missed rather than being skipped because a livelier peer is already up to date. On a
        // first run that floor is zero, which is how the household LifeOps already knows about
        // reaches a freshly-installed People without an import step.
        val floor = peers.minOfOrNull { peer ->
            store.read(peer)?.ackFor(Peers.LIFEOPS) ?: 0L
        } ?: 0L
        val changes = personRepository.changesSince(floor).map { (version, packet) ->
            VersionedPacket(version, packet)
        }
        store.write(
            engine.buildOutbound(changes = changes, acks = peers.associateWith { readCursor(it) })
        )

        Summary(peersSeen = seen, created = created, updated = updated, published = changes.size)
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
