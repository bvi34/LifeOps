package com.people.app.sync

/**
 * One person on the wire.
 *
 * This is the **shared vocabulary** of the People seam: the fields every peer agrees a person has.
 * It is deliberately smaller than any peer's own row. LifeOps' weather-comfort tolerances and
 * Health's baseline temperature are *not* here, and that is the point — a field only one peer can
 * edit has no business on a shared wire, because syncing it would make every other peer an
 * authority on data it cannot show, cannot change, and would happily overwrite with a stale copy.
 * Peers keep their own columns; the seam carries identity.
 *
 * [updatedAt] is the merge clock (epoch millis, see [PersonMerge]) and [personKey] is the identity
 * that survives both peers having invented their own row id for the same human (see [PersonBinder]).
 */
data class PersonPacket(
    val personKey: String,
    val name: String,
    val relationship: String? = null,
    /** ISO `yyyy-MM-dd`. A birth date is a calendar fact, not an instant. */
    val birthDate: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val note: String? = null,
    val archived: Boolean = false,
    /**
     * Whether the directory counts this person as a **household member** — the flag that decides
     * whether Health grows a profile for them (see [PeopleSyncEngine]'s creation policy).
     *
     * Nullable, and that is the whole design: `null` means *this peer has no opinion*, not "no". Only
     * People has a column for it; LifeOps has none and publishes null, so a LifeOps edit can never
     * silently un-flag somebody just by being the more recent write. [PersonMerge] treats it exactly
     * like the nullable text fields — the newer record wins only where it actually says something.
     */
    val household: Boolean? = null,
    val updatedAt: Long = 0L,
    /**
     * A withdrawal, not an erasure. A peer sets this when a person is removed on its side; every
     * other peer **archives** rather than deletes — see [PersonMerge.merge]. Losing a household
     * member's whole history because another app dropped a row is not a recoverable mistake, and
     * this seam is not confident enough to make it.
     */
    val deleted: Boolean = false
)

/**
 * What one peer publishes in a round.
 *
 * Symmetric, unlike the Citation seam's outbound/inbound pair — there, one side sends telemetry and
 * the other sends intents, so the two envelopes carry different things. Here both peers say exactly
 * the same kind of thing about the same people, so there is one envelope type and each peer writes
 * its own copy of it.
 *
 * [acks] is keyed by peer name: "the highest version of *yours* I have durably taken". A map rather
 * than the single number two peers need today, so a third peer (Health is the obvious next one) can
 * join the folder without a format change or a migration of anyone's cursor.
 */
data class PeerEnvelope(
    val peer: String,
    val packets: List<VersionedPacket>,
    val acks: Map<String, Long> = emptyMap()
) {
    /** The highest version of [otherPeer]'s packets this peer has consumed. */
    fun ackFor(otherPeer: String): Long = acks[otherPeer] ?: 0L
}

/** A packet stamped with its sending peer's monotonic version — the mailbox's unit of work. */
data class VersionedPacket(val version: Long, val payload: PersonPacket)

/**
 * What a peer just did to its own roster — what the thing that triggers a sync round needs to know.
 *
 * The distinction is not cosmetic. Publishing is enough for an edit: the row already has a key the
 * other peers know, so they will bind it. A **new** person is different — this peer may have just
 * invented a second row for somebody the seam already carries under another key, and the packet that
 * would prove it is behind this peer's cursor and will never be sent again. So a create asks for a
 * full re-read (see the `rescan` flag on each app's sync service) and the binder gets another look.
 */
enum class LocalRosterChange {
    PERSON_ADDED,
    PERSON_EDITED
}

/** The peer names this seam knows. Free-form strings on the wire; these are the ones we ship. */
object Peers {
    const val PEOPLE = "people"
    const val LIFEOPS = "lifeops"

    /** Reserved: Health is the next peer to join, and keys its profiles off the same identity. */
    const val HEALTH = "health"
}
