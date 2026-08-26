package com.people.app.sync

import com.citation.core.sync.Mailbox

/**
 * One peer's view of its own roster, framework-free.
 *
 * Both sides of the seam implement this over their own Room database — People over `people.db`,
 * LifeOps over its `persons` table — which is what lets the reconciliation below be written **once**
 * and unit-tested against an in-memory fake. A merge rule implemented twice is a merge rule that
 * disagrees with itself the first time either copy is edited.
 */
interface PeerRoster {

    /** Everyone this peer holds, reduced to what binding looks at. */
    fun candidates(): List<PersonBinder.Candidate>

    /** The current record for a local row, as a packet, or null if it has gone. */
    fun read(localId: String): PersonPacket?

    /** Store a merged record against an existing row, without stamping a new outgoing version. */
    fun update(localId: String, packet: PersonPacket)

    /** Create a row for a person this peer has never seen, carrying the packet's key. */
    fun create(packet: PersonPacket): String
}

/**
 * Whether an arriving person this peer has never seen becomes a row here.
 *
 * A predicate on the packet rather than a flat yes/no, because the interesting peer is neither:
 * Health grows a profile for a **household member** and for nobody else, and which is which is the
 * directory's answer to give, not Health's. Putting the question on the packet is what lets People
 * own that decision — you tick somebody as household in the directory and Health picks them up —
 * without Health having to reach into another app's database to ask.
 */
fun interface CreationPolicy {

    fun createsRowFor(packet: PersonPacket): Boolean

    companion object {
        /**
         * For a peer that holds the household outright — People and LifeOps both do; a person either
         * app learns about is a person the other should know.
         */
        val ALWAYS = CreationPolicy { true }

        /**
         * For a peer that only *annotates* people. Health is the case this exists for: it tracks
         * temperatures and doses, and a roster that silently grew a medical profile for every adult
         * in the house — including the ones nobody is tracking — would be worse than no sync at all.
         * So it takes the people the directory has marked as household members, and leaves the rest.
         *
         * A packet with **no opinion** (`household == null`, which is every packet LifeOps writes,
         * since it has no column for the flag) does not create. Silence is not consent here.
         */
        val HOUSEHOLD_ONLY = CreationPolicy { it.household == true }
    }
}

/**
 * One round of People ↔ peer sync, over the same mailbox spine Citation rides.
 *
 * Two pure steps, transport outside:
 *
 *  1. [buildOutbound] — publish the local changes above what the other peers have acknowledged,
 *     plus our own ack cursors so they can stop resending.
 *  2. [applyInbound] — bind each arriving packet to a local person (or create one), merge it, and
 *     report how far we consumed so the cursor can be persisted.
 *
 * Idempotence comes from the same place it does on the Citation seam: the monotonic version. A round
 * re-applied is a round that changes nothing, so a half-finished sync is always safe to retry — and
 * unlike the Citation seam, both directions carry the same packet type, so this one engine serves
 * both peers rather than each end owning half the protocol.
 */
class PeopleSyncEngine(
    private val peer: String,
    private val roster: PeerRoster,
    /** Which arriving strangers become rows here — see [CreationPolicy]. */
    private val creationPolicy: CreationPolicy = CreationPolicy.ALWAYS
) {

    /** What one round did, for status and logging. */
    data class Applied(
        val created: Int,
        val updated: Int,
        val unchanged: Int,
        val ackedThrough: Long
    ) {
        val touched: Int get() = created + updated
    }

    /**
     * The envelope this peer publishes. [changes] is everything it has recorded since the other
     * peers last acknowledged — derived from rows, not held in memory, so it survives a restart
     * without a durable outbox of its own.
     */
    fun buildOutbound(changes: List<VersionedPacket>, acks: Map<String, Long>): PeerEnvelope =
        PeerEnvelope(
            peer = peer,
            packets = changes.sortedBy { it.version },
            acks = acks
        )

    /**
     * Fold another peer's envelope in. [sinceVersion] is our persisted cursor for that peer;
     * anything at or below it has already been taken and is ignored, so a peer that keeps resending
     * unacked packets (as it should) costs nothing.
     *
     * Returns how far to advance the cursor — the highest version *resolved*, not merely the highest
     * that changed something. A packet that merged to no change, or that this peer's creation policy
     * declined, is still a packet we have taken: leaving the cursor behind it would make the round
     * repeat for ever. The one exception is a packet we could not resolve at all — see [unresolved]
     * in the body — which is left unacked so the next round gets to decide it properly.
     */
    fun applyInbound(incoming: PeerEnvelope, sinceVersion: Long): Applied {
        val mailbox = Mailbox<PersonPacket, PersonPacket>()
        mailbox.restore(outVersion = 0L, inboxCursor = sinceVersion)
        mailbox.deliver(incoming.packets.map { Mailbox.Versioned(it.version, it.payload) })

        val pending = mailbox.pending()
        var created = 0
        var updated = 0
        var unchanged = 0

        // The packet this round stopped at, if any. Nothing past it is acked, so there is no point
        // applying it either — it would simply be redone next round. See the cursor calculation.
        var unresolved: Long? = null

        for (item in pending) {
            val packet = item.payload
            when (val decision = PersonBinder.bind(packet, roster.candidates())) {
                is PersonBinder.Decision.Bind -> {
                    val local = roster.read(decision.localId)
                    if (local == null) {
                        // The row went away between binding and reading — a delete racing a sync.
                        // Neither answer to "so create it?" is right: recreating resurrects the row
                        // somebody just deleted, and dropping it loses a packet the cursor is about
                        // to move past for ever. So do neither and don't ack it. Next round the
                        // binder sees a roster without that row, reaches Decision.Create, and the
                        // creation policy gets its say on a question that is now unambiguous.
                        unresolved = item.version
                        break
                    } else {
                        val result = PersonMerge.merge(local, packet)
                        if (result.changed) {
                            roster.update(decision.localId, result.merged)
                            updated++
                        } else {
                            unchanged++
                        }
                    }
                }

                PersonBinder.Decision.Create -> {
                    // A tombstone for somebody we never had is not a person to create — and a peer
                    // whose policy declines this packet (Health, for anyone the directory has not
                    // marked as a household member) only keeps up with what it already tracks.
                    // Either way the cursor still advances: the packet *was* taken, and a cursor
                    // left behind it would replay the round for ever.
                    if (packet.deleted || !creationPolicy.createsRowFor(packet)) {
                        unchanged++
                    } else {
                        roster.create(packet)
                        created++
                    }
                }
            }
        }

        val ackedThrough = unresolved
            ?.let { maxOf(sinceVersion, it - 1) }
            ?: pending.maxOfOrNull { it.version }
            ?: sinceVersion
        mailbox.consumeThrough(ackedThrough)
        return Applied(created, updated, unchanged, ackedThrough)
    }
}
