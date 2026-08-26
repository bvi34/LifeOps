package com.health.app.data.repository

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
        cached ?: runBlocking { repository.bindingCandidates() }.also { cached = it }

    override fun read(localId: String): PersonPacket? = runBlocking {
        repository.profileEntity(localId)?.toPacket()
    }

    override fun update(localId: String, packet: PersonPacket) = runBlocking {
        repository.applyMergedProfile(localId, packet)
        cached = null
    }

    /**
     * Never called: Health runs the engine with `createUnknown = false`. It is here because the
     * interface requires it, and it throws rather than quietly inventing a medical profile if that
     * policy is ever changed by accident.
     */
    override fun create(packet: PersonPacket): String =
        error("Health is a bind-only peer and does not create profiles from the seam")
}

/**
 * Health's side of the People sync seam — the third peer, and the one that participates differently.
 *
 * People and LifeOps each hold the household outright: a person either learns about is a person the
 * other should know, so both create freely. Health **annotates** people rather than holding them. It
 * tracks temperatures and doses for whoever is actually ill, and a medical profile that silently
 * appeared for every adult in the house would be worse than no sync at all — so it runs the engine
 * with `createUnknown = false` and only keeps in step with the people it has been told to track.
 *
 * What it gains from the seam is worth having anyway: a person added in People arrives with their
 * **birth date**, which is exactly what Health's fever rules need to know they are looking at a
 * six-week-old rather than an adult — and which nobody wants to type twice.
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

    suspend fun sync(peers: List<String>): Summary = withContext(Dispatchers.IO) {
        roundLock.withLock { round(peers) }
    }

    private suspend fun round(peers: List<String>): Summary {
        val store = PeopleEnvelopeStore(syncDir)
        val engine = PeopleSyncEngine(Peers.HEALTH, HealthRoster(repository), createUnknown = false)

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

        // Publish above the *lowest* ack across peers, so a peer that has been away still receives
        // what it missed instead of being skipped because a livelier one is already up to date.
        val floor = peers.minOfOrNull { peer ->
            store.read(peer)?.ackFor(Peers.HEALTH) ?: 0L
        } ?: 0L
        val changes = repository.profileChangesSince(floor).map { (version, packet) ->
            VersionedPacket(version, packet)
        }
        store.write(
            engine.buildOutbound(changes = changes, acks = peers.associateWith { readCursor(it) })
        )

        return Summary(peersSeen = seen, updated = received, published = changes.size, error = failure)
    }
}
