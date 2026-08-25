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

    /** Snapshot the roster once per round rather than per packet — it can't change mid-round. */
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
 */
class PeopleSyncService(
    private val repository: PeopleRepository,
    private val syncDir: File,
    private val readCursor: (String) -> Long,
    private val writeCursor: (String, Long) -> Unit,
    private val writeLastPublishedVersion: (Long) -> Unit,
    private val paletteFor: (Int) -> Long = { index -> PROFILE_COLORS[index % PROFILE_COLORS.size] }
) {

    suspend fun sync(peers: List<String>): SyncStatus = withContext(Dispatchers.IO) {
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

        // Publish everything the other peers haven't acknowledged. The floor is the *lowest* ack
        // across peers, so a peer that has been away for a week still receives what it missed
        // instead of being quietly skipped because a livelier peer is already up to date.
        val floor = peers.minOfOrNull { peerAckOfUs(store, it) } ?: 0L
        val changes = repository.changesSince(floor).map { (version, packet) ->
            VersionedPacket(version, packet)
        }
        val outbound = engine.buildOutbound(
            changes = changes,
            acks = peers.associateWith { readCursor(it) }
        )
        store.write(outbound)
        writeLastPublishedVersion(repository.currentSyncVersion())

        SyncStatus(
            lastRunAt = System.currentTimeMillis(),
            peersSeen = seen,
            received = received,
            sent = changes.size,
            error = failure
        )
    }

    /** How much of *our* output a peer says it has taken — read from that peer's own envelope. */
    private fun peerAckOfUs(store: PeopleEnvelopeStore, peer: String): Long =
        store.read(peer)?.ackFor(Peers.PEOPLE) ?: 0L

    companion object {
        /** The palette a person arriving over the seam is assigned from — distinct at a glance. */
        val PROFILE_COLORS = listOf(
            0xFF5A5ABF, 0xFF2C7A7B, 0xFFB7791F, 0xFF6B46C1,
            0xFF2B6CB0, 0xFFB83280, 0xFF2F855A, 0xFFC05621
        )
    }
}
