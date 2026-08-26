package com.people.app.data.repository

import com.people.app.data.model.SyncStatus
import com.people.app.sync.PeerEnvelope
import com.people.app.sync.PeerRoster
import com.people.app.sync.PeopleEnvelopeStore
import com.people.app.sync.Peers
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
 * People's roster as the engine sees it.
 *
 * The suspend/blocking bridge is deliberate and confined to this one class: [PeerRoster] is a pure
 * interface so the reconciliation can be JVM-tested without Room or coroutines, and Room's DAO is
 * suspending. Rather than colour the whole sync contract `suspend` for the sake of one
 * implementation, the blocking happens here — [PeopleSyncService.sync] already runs on the IO
 * dispatcher, so nothing on a main thread ever waits on it.
 */
private class PeopleRoster(
    private val repository: PeopleRepository,
    private val paletteFor: (Int) -> Long
) : PeerRoster {

    /**
     * Snapshot the roster rather than re-reading it for every packet, and drop the snapshot whenever
     * a write invalidates it. Both writes invalidate: a create adds a row to bind against, and a
     * merge can change a row's `personKey` (identity converges on the lower of the two — see
     * `PersonMerge`). That second case is easy to miss and matters now that three peers land in one
     * round: the packet from the second peer would be bound against the key the row held before the
     * first peer's packet moved it.
     */
    private var cached: List<PersonBinder.Candidate>? = null

    override fun candidates(): List<PersonBinder.Candidate> = cached ?: runBlocking {
        repository.allPeople().map { person ->
            PersonBinder.Candidate(person.id, person.personKey, person.name, person.email)
        }
    }.also { cached = it }

    override fun read(localId: String): PersonPacket? = runBlocking {
        repository.personEntity(localId)?.toPacket()
    }

    override fun update(localId: String, packet: PersonPacket) = runBlocking {
        repository.applyMerged(localId, packet)
        cached = null
    }

    override fun create(packet: PersonPacket): String = runBlocking {
        val id = repository.createFromPacket(packet, paletteFor(candidates().size))
        cached = null
        id
    }
}

/**
 * Drives one People ↔ peers sync round over the shared folder.
 *
 * Both apps live in the Operations Sandbox's single process and share one `filesDir`, so the
 * "transport" is a directory both can see — exactly the arrangement the Citation seam uses, and for
 * the same reason: it needs no server, works offline, and is a plain file both sides can be tested
 * against. Nothing here knows how the other peer stores its people; it only reads their envelope.
 *
 * A round is: read every other peer's envelope, fold the packets above our cursor for that peer into
 * our roster, persist the advanced cursors, then publish our own changes with the acks that let the
 * other side prune. Re-running it changes nothing, so it is safe on every app open.
 *
 * Rounds are serialized by a mutex. Idempotence makes a *repeated* round free, but it says nothing
 * about two *overlapping* ones: each snapshots the roster once and then binds every packet against
 * that snapshot, so two rounds racing over the same envelope would both decide the same arriving
 * person is unknown and both create a row for them. Opening the app while an edit is publishing is
 * exactly that race, and it is common rather than exotic.
 */
class PeopleSyncService(
    private val repository: PeopleRepository,
    private val syncDir: File,
    private val readCursor: (String) -> Long,
    private val writeCursor: (String, Long) -> Unit,
    private val writeLastPublishedVersion: (Long) -> Unit,
    private val paletteFor: (Int) -> Long = { index -> PROFILE_COLORS[index % PROFILE_COLORS.size] }
) {

    private val roundLock = Mutex()

    /**
     * @param rescan rewind every cursor first, so the round re-reads the other peers' rosters in
     *   full. Set when this peer has just gained a person of its own ([com.people.app.sync.LocalRosterChange.PERSON_ADDED]):
     *   the packet that would bind them to the household record is behind the cursor and would
     *   otherwise never be seen again. Re-applying settled packets is free — they merge to no change.
     */
    suspend fun sync(peers: List<String>, rescan: Boolean = false): SyncStatus = withContext(Dispatchers.IO) {
        roundLock.withLock { round(peers, rescan) }
    }

    private suspend fun round(peers: List<String>, rescan: Boolean): SyncStatus {
        if (rescan) peers.forEach { writeCursor(it, 0L) }
        val store = PeopleEnvelopeStore(syncDir)
        val roster = PeopleRoster(repository, paletteFor)
        val engine = PeopleSyncEngine(Peers.PEOPLE, roster)

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
                    // Persist the cursor even when nothing changed: the packets were still taken,
                    // and a cursor left behind them replays the whole round next time.
                    if (applied.ackedThrough > cursor) writeCursor(peer, applied.ackedThrough)
                }
                .onFailure { failure = it.message ?: it::class.java.simpleName }
        }

        // Publish the whole roster, not a delta above what the peers have acknowledged.
        //
        // A delta is the obvious optimization and it was the wrong one. Pruning to the lowest ack
        // means a peer can only ever bind a person while that person's packet is still on the wire —
        // and once every peer has acked it, it is gone for good. So a peer that later changes what
        // it holds (Health, the moment you start tracking somebody it had been ignoring) had no way
        // back to that person's record: no birth date, and two keys that never converge. A household
        // is a handful of rows, so the envelope stays a few kilobytes and any peer can bind at any
        // moment. The cursors still do their real job — suppressing re-application — so a settled
        // round is still a no-op.
        val changes = repository.changesSince(0L).map { (version, packet) ->
            VersionedPacket(version, packet)
        }
        val outbound = engine.buildOutbound(
            changes = changes,
            acks = peers.associateWith { readCursor(it) }
        )
        store.write(outbound)
        writeLastPublishedVersion(repository.currentSyncVersion())

        return SyncStatus(
            lastRunAt = System.currentTimeMillis(),
            peersSeen = seen,
            received = received,
            sent = changes.size,
            error = failure
        )
    }

    companion object {
        /** The palette a person arriving over the seam is assigned from — distinct at a glance. */
        val PROFILE_COLORS = listOf(
            0xFF5A5ABF, 0xFF2C7A7B, 0xFFB7791F, 0xFF6B46C1,
            0xFF2B6CB0, 0xFFB83280, 0xFF2F855A, 0xFFC05621
        )
    }
}
