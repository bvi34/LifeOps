package com.citation.core.sync

/**
 * The **mailbox pattern** — the same seam every LifeOps peer uses to sync, reused here so Citation
 * is just another peer on the spine.
 *
 * A peer exposes two things:
 *  - **state/outbox**: the packets it has produced but not yet had acknowledged, each stamped with a
 *    **monotonic version**. Version is a per-peer, never-decreasing counter; the other side tracks
 *    "last version I've seen from you" and pulls everything above it. That single number makes sync
 *    idempotent and resumable — re-delivering an already-seen version is a no-op, and a peer that was
 *    offline for a week just asks for everything past its cursor.
 *  - **inbox**: intents delivered *to* this peer, consumed and acknowledged by version likewise.
 *
 * This class is the transport-agnostic core of that: it has no idea whether the bytes move by file
 * drop, HTTP, or shared folder. It only guarantees the monotonic-version bookkeeping.
 */
class Mailbox<Out, In> {

    /** An outgoing item stamped with its monotonic version. */
    data class Versioned<T>(val version: Long, val payload: T)

    private val outbox = ArrayList<Versioned<Out>>()
    private var outVersion = 0L

    private val inbox = ArrayList<Versioned<In>>()
    private var lastConsumedInVersion = 0L

    /** The highest version this peer has produced. Peers exchange this to know what's new. */
    val currentOutVersion: Long get() = outVersion

    /** The highest inbound version this peer has consumed. Its pull cursor for the other side. */
    val inboxCursor: Long get() = lastConsumedInVersion

    /**
     * Enqueue an outgoing [payload], assigning it the next monotonic version. Returns the stamped
     * item so the caller can persist the version alongside the source record.
     */
    @Synchronized
    fun post(payload: Out): Versioned<Out> {
        val v = ++outVersion
        val item = Versioned(v, payload)
        outbox.add(item)
        return item
    }

    /**
     * Everything in the outbox with version strictly greater than [sinceVersion] — what a peer that
     * last saw `sinceVersion` should now receive. Ordered by version.
     */
    @Synchronized
    fun outboxSince(sinceVersion: Long): List<Versioned<Out>> =
        outbox.filter { it.version > sinceVersion }.sortedBy { it.version }

    /**
     * Drop acknowledged items (version ≤ [ackVersion]) from the outbox. Safe to over-acknowledge; a
     * lower value than already dropped is a no-op.
     */
    @Synchronized
    fun ackOutbox(ackVersion: Long) {
        outbox.removeAll { it.version <= ackVersion }
    }

    /**
     * Accept inbound [items] into the inbox, ignoring any at-or-below the consumed cursor so a
     * re-delivery is idempotent.
     */
    @Synchronized
    fun deliver(items: List<Versioned<In>>) {
        for (item in items) {
            if (item.version > lastConsumedInVersion && inbox.none { it.version == item.version }) {
                inbox.add(item)
            }
        }
    }

    /** Undelivered inbound items above the consumed cursor, ordered by version. */
    @Synchronized
    fun pending(): List<Versioned<In>> =
        inbox.filter { it.version > lastConsumedInVersion }.sortedBy { it.version }

    /**
     * Mark inbound items up to and including [version] consumed, advancing the cursor and clearing
     * them from the inbox. Never rewinds the cursor.
     */
    @Synchronized
    fun consumeThrough(version: Long) {
        if (version > lastConsumedInVersion) lastConsumedInVersion = version
        inbox.removeAll { it.version <= lastConsumedInVersion }
    }

    /** Restore bookkeeping after a restart so versions continue without gaps or reuse. */
    @Synchronized
    fun restore(outVersion: Long, inboxCursor: Long) {
        if (outVersion > this.outVersion) this.outVersion = outVersion
        if (inboxCursor > this.lastConsumedInVersion) this.lastConsumedInVersion = inboxCursor
    }

    /**
     * Re-populate the outbox after a restart from a durable copy the sender persisted itself (each
     * item at the version it was originally [post]ed at). The in-memory outbox is otherwise empty on
     * relaunch — [restore] only recovers the counters — so without this, packets produced in a past
     * session that were never acknowledged would never be resent. Dedupes by version (a re-seed or an
     * item already posted this session is ignored) and keeps the outbox ordered. Pair with [restore]
     * so [currentOutVersion] already sits at-or-above every seeded version.
     */
    @Synchronized
    fun seedOutbox(items: List<Versioned<Out>>) {
        for (item in items) {
            if (outbox.none { it.version == item.version }) outbox.add(item)
        }
        outbox.sortBy { it.version }
    }
}
