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
    /**
     * Whether an arriving person this peer has never seen becomes a row here.
     *
     * True for a peer that holds the household outright — People and LifeOps both do; a person
     * either app learns about is a person the other should know.
     *
     * False for a peer that only *annotates* people. Health is the case this exists for: it tracks
     * temperatures and doses for the one or two people who are actually ill, and a household roster
     * that silently grew a medical profile for everyone — including the adults nobody is tracking —
     * would be worse than no sync at all. A bind-only peer still keeps every person it *has* chosen
     * in step (names, birth dates, withdrawals), and adding someone stays an explicit act in that
     * app. It also removes the awkward corollary: a peer that never auto-creates can delete its own
     * row without the next round handing the person straight back.
     */
    private val createUnknown: Boolean = true
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
     * Returns how far to advance the cursor — the highest version *seen*, not merely the highest
     * that changed something. A packet that merged to no change is still a packet we have taken,
     * and leaving the cursor behind it would make the round repeat forever.
     */
    fun applyInbound(incoming: PeerEnvelope, sinceVersion: Long): Applied {
        val mailbox = Mailbox<PersonPacket, PersonPacket>()
        mailbox.restore(outVersion = 0L, inboxCursor = sinceVersion)
        mailbox.deliver(incoming.packets.map { Mailbox.Versioned(it.version, it.payload) })

        val pending = mailbox.pending()
        var created = 0
        var updated = 0
        var unchanged = 0

        for (item in pending) {
            val packet = item.payload
            when (val decision = PersonBinder.bind(packet, roster.candidates())) {
                is PersonBinder.Decision.Bind -> {
                    val local = roster.read(decision.localId)
                    if (local == null) {
                        // The row went away between binding and reading (a delete racing a sync).
                        // Treat it as new rather than dropping the packet on the floor — unless this
                        // peer is bind-only, where re-creating the row would resurrect exactly the
                        // profile the user has just deleted (and, for a peer whose `create` refuses
                        // outright, would abort the whole round for every packet behind this one).
                        if (createUnknown && !packet.deleted) {
                            roster.create(packet)
                            created++
                        } else {
                            unchanged++
                        }
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
                    // A tombstone for somebody we never had is not a person to create — and a
                    // bind-only peer does not create at all, it only keeps up with what it already
                    // tracks. Either way the cursor still advances: the packet *was* taken, and a
                    // cursor left behind it would replay the round for ever.
                    if (packet.deleted || !createUnknown) {
                        unchanged++
                    } else {
                        roster.create(packet)
                        created++
                    }
                }
            }
        }

        val ackedThrough = pending.maxOfOrNull { it.version } ?: sinceVersion
        mailbox.consumeThrough(ackedThrough)
        return Applied(created, updated, unchanged, ackedThrough)
    }
}
