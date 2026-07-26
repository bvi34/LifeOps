package com.citation.core.sync

/**
 * Orchestrates one sync round over the mailbox, split into two pure steps so the actual transport
 * (file drop, HTTP, shared folder) stays outside and the protocol logic stays testable:
 *
 *  1. [buildOutbound] — snapshot what to send: every unacked up-packet plus our intent-cursor.
 *  2. [applyInbound] — fold LifeOps' response back in: prune our outbox to their ack, deliver their
 *     intents idempotently, hand each new intent to [reconcile], then advance our intent-cursor.
 *
 * Because the mailbox already dedupes by version, re-applying the same inbound envelope is a no-op —
 * a dropped connection mid-round is safe to retry.
 */
class SyncEngine(
    private val mailbox: Mailbox<UpPacket, AcquireBookIntent>,
    private val peer: String = com.citation.core.key.EntityKey.CITATION_NAMESPACE
) {

    /** Snapshot the envelope to send up. Safe to call repeatedly; it mutates nothing. */
    fun buildOutbound(): OutboundEnvelope =
        OutboundEnvelope(
            peer = peer,
            packets = mailbox.outboxSince(0),
            ackedIntentVersion = mailbox.inboxCursor
        )

    /**
     * Apply LifeOps' response: prune the outbox to [InboundEnvelope.ackedPacketVersion], deliver the
     * intents, run [reconcile] on each newly-pending intent (the caller binds-or-creates + persists),
     * then consume through the highest delivered version so it isn't reprocessed next round.
     *
     * @return the intents reconciled this round (for logging / status), in version order.
     */
    fun applyInbound(
        inbound: InboundEnvelope,
        reconcile: (AcquireBookIntent) -> Unit
    ): List<AcquireBookIntent> {
        mailbox.ackOutbox(inbound.ackedPacketVersion)
        mailbox.deliver(inbound.intents)

        val pending = mailbox.pending()
        pending.forEach { reconcile(it.payload) }
        pending.maxOfOrNull { it.version }?.let { mailbox.consumeThrough(it) }
        return pending.map { it.payload }
    }
}
