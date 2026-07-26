package com.citation.core.sync

/**
 * The two envelopes exchanged in one sync round. Both carry an **acknowledgement cursor** alongside
 * their payload, which is what makes the mailbox monotonic-version protocol converge: each side tells
 * the other "the highest version of yours I've durably taken", so nothing is re-processed and a peer
 * that missed a round just resends everything past the cursor.
 */

/** What Citation sends **up** to LifeOps. */
data class OutboundEnvelope(
    /** The sending peer's namespace (Citation = `ER`). */
    val peer: String,
    /** Unacked up-packets (telemetry + notes), each with its monotonic version. */
    val packets: List<Mailbox.Versioned<UpPacket>>,
    /** The highest intent version Citation has consumed — LifeOps prunes its intent outbox to this. */
    val ackedIntentVersion: Long
)

/** What LifeOps returns **down** to Citation. */
data class InboundEnvelope(
    /** New acquire intents for Citation, each with its monotonic version. */
    val intents: List<Mailbox.Versioned<AcquireBookIntent>>,
    /** The highest packet version LifeOps has stored — Citation prunes its outbox to this. */
    val ackedPacketVersion: Long
)
