package com.health.app.data.repository

import com.people.app.sync.CreationPolicy
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
 * Health's roster as the engine sees it.
 *
 * The suspend/blocking bridge is confined here so [PeerRoster] can stay a pure interface — which is
 * what lets the reconciliation be JVM-tested without Room. [HealthSyncService.sync] already runs on
 * the IO dispatcher, so nothing on a main thread waits here.
 */
private class HealthRoster(private val repository: HealthRepository) : PeerRoster {

    /**
     * Snapshot the roster rather than re-reading it per packet, dropped on any write that
     * invalidates it. Health never creates, but a merge can still move a profile's `personKey`
     * (identity converges on the lower of the two, see `PersonMerge`) — and with People and LifeOps
     * both landing in the same round, the second peer's packet would otherwise be bound against the
     * key the profile held before the first peer's packet moved it. Health binds by *name* until it
     * has a key, so a stale key here is the difference between keeping a profile in step and
     * silently ignoring every packet about them.
     */
    private var cached: List<PersonBinder.Candidate>? = null

    override fun candidates(): List<PersonBinder.Candidate> =
        cached ?: runBlocking { repository.profiles.bindingCandidates() }.also { cached = it }

    override fun read(localId: String): PersonPacket? = runBlocking {
        repository.profiles.profileEntity(localId)?.toPacket()
    }

    override fun update(localId: String, packet: PersonPacket) = runBlocking {
        repository.profiles.applyMergedProfile(localId, packet)
        cached = null
    }

    /**
     * Only ever reached for a packet [CreationPolicy.HOUSEHOLD_ONLY] accepted — somebody the
     * directory has ticked as a household member. Everyone else never gets this far.
     */
    override fun create(packet: PersonPacket): String = runBlocking {
        repository.profiles.createFromPacket(packet).also { cached = null }
    }
}

/**
 * Health's side of the People sync seam — the third peer, and the one that participates differently.
 *
 * People and LifeOps each hold the household outright: a person either learns about is a person the
 * other should know, so both create freely. Health **annotates** people rather than holding them, so
 * it runs the engine with [CreationPolicy.HOUSEHOLD_ONLY]: it grows a profile for somebody the
 * directory has ticked as a household member, and for nobody else. A medical profile that silently
 * appeared for every adult in the house would be worse than no sync at all — but so is a seam that
 * can never hand Health anybody, which is what "Health only keeps up with people it already tracks"
 * amounted to in practice. Putting the decision on the packet moves it to the app that should be
 * making it.
 *
 * What Health gains is worth having: a household member arrives with their **birth date**, which is
 * exactly what the fever rules need to know they are looking at a six-week-old rather than an adult,
 * and which nobody wants to type twice.
 *
 * What it never publishes is [com.health.app.data.db.entities.ProfileEntity.notes]; see
 * `HealthRepository.toPacket`.
 */
class HealthSyncService(
    private val repository: HealthRepository,
    private val syncDir: File,
    private val readCursor: (String) -> Long,
    private val writeCursor: (String, Long) -> Unit
) {

    /** Outcome of one round, for status/logging — the same shape LifeOps' side reports. */
    data class Summary(
        val peersSeen: List<String>,
        val updated: Int,
        val published: Int,
        val error: String? = null
    )

    /**
     * Rounds are serialized: each snapshots the roster once and binds every packet against that
     * snapshot, so two overlapping rounds would each decide the same arriving person is unknown.
     * A profile edit publishing while the screen's own round is still running is exactly that race.
     */
    private val roundLock = Mutex()

    /**
     * @param rescan rewind every cursor first, so the round re-reads the household in full. Set when
     *   Health has just gained a profile of its own: the packet that would bind that profile to the
     *   directory's record of the same person — and carry their birth date over — is behind the
     *   cursor and would otherwise never be seen again.
     */
    suspend fun sync(peers: List<String>, rescan: Boolean = false): Summary = withContext(Dispatchers.IO) {
        roundLock.withLock { round(peers, rescan) }
    }

    private suspend fun round(peers: List<String>, rescan: Boolean): Summary {
        if (rescan) peers.forEach { writeCursor(it, 0L) }
        val store = PeopleEnvelopeStore(syncDir)
        val engine = PeopleSyncEngine(Peers.HEALTH, HealthRoster(repository), CreationPolicy.HOUSEHOLD_ONLY)

        var received = 0
        val seen = ArrayList<String>()
        var failure: String? = null

        for (peer in peers) {
            val envelope = store.read(peer) ?: continue
            seen += peer
            val cursor = readCursor(peer)
            runCatching { engine.applyInbound(envelope, cursor) }
                .onSuccess { applied ->
                    received += applied.touched
                    // Advance even when nothing changed: those packets were taken, and a cursor left
                    // behind them replays the round for ever.
                    if (applied.ackedThrough > cursor) writeCursor(peer, applied.ackedThrough)
                }
                .onFailure { failure = it.message ?: it::class.java.simpleName }
        }

        // Publish the whole roster, not a delta above what the peers have acknowledged. Pruning to
        // the lowest ack meant a peer could only bind a person while that person's packet was still
        // on the wire, and once every peer had acked it, it was gone for good — so a peer that later
        // changed what it holds had no way back to that person's record. A household is a handful of
        // rows; the cursors still suppress re-application, so a settled round is still a no-op.
        val changes = repository.profiles.profileChangesSince(0L).map { (version, packet) ->
            VersionedPacket(version, packet)
        }
        store.write(
            engine.buildOutbound(changes = changes, acks = peers.associateWith { readCursor(it) })
        )

        return Summary(peersSeen = seen, updated = received, published = changes.size, error = failure)
    }
}
