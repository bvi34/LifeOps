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
        val peerSeen: Boolean,
        val created: Int,
        val updated: Int,
        val published: Int,
        val ackedThrough: Long
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
     * Run one round: take People's envelope from the shared folder, fold in everything above our
     * cursor, then publish our own changes with the ack that lets People prune.
     *
     * Idempotent — re-running it changes nothing — so it is safe on every app launch, which is where
     * [com.lifeops.app.LifeOpsApp] calls it from.
     */
    suspend fun sync(): Summary = withContext(Dispatchers.IO) {
        val store = PeopleEnvelopeStore(syncDir)
        val engine = PeopleSyncEngine(Peers.LIFEOPS, LifeOpsRoster())

        val incoming = store.read(Peers.PEOPLE)
        val cursor = readCursor(Peers.PEOPLE)
        val applied = incoming?.let { engine.applyInbound(it, cursor) }
        // Persist the cursor even when nothing changed: those packets were still taken, and a cursor
        // left behind them replays the whole round next launch.
        if (applied != null && applied.ackedThrough > cursor) {
            writeCursor(Peers.PEOPLE, applied.ackedThrough)
        }

        // Publish everything People hasn't acknowledged yet. On a first run that is the whole
        // household LifeOps already knows about — which is exactly how an existing roster reaches a
        // freshly-installed People without an import step.
        val theirAckOfUs = incoming?.ackFor(Peers.LIFEOPS) ?: 0L
        val changes = personRepository.changesSince(theirAckOfUs).map { (version, packet) ->
            VersionedPacket(version, packet)
        }
        val outbound = engine.buildOutbound(
            changes = changes,
            acks = mapOf(Peers.PEOPLE to readCursor(Peers.PEOPLE))
        )
        store.write(outbound)

        Summary(
            peerSeen = incoming != null,
            created = applied?.created ?: 0,
            updated = applied?.updated ?: 0,
            published = changes.size,
            ackedThrough = applied?.ackedThrough ?: cursor
        )
    }
}
