package com.people.app.sync

/**
 * An in-memory [PeerRoster] standing in for one peer's database, so a whole round can be run and
 * asserted on the JVM. Row ids are local and deliberately *not* the person key — that gap is the
 * thing the binder exists to close, so a fake that papered over it would test nothing.
 */
class FakeRoster(
    private val idPrefix: String,
    /**
     * Mirrors Health's roster, which refuses outright rather than quietly inventing a medical
     * profile from the seam. A fake that silently created instead would hide the very thing the
     * bind-only policy is there to prevent.
     */
    private val refuseCreate: Boolean = false
) : PeerRoster {

    val rows = LinkedHashMap<String, PersonPacket>()

    /** Rows that are still visible to binding but have gone by the time they are read. */
    private val vanished = HashSet<String>()

    private var nextId = 1

    /** Record a locally-authored person, as a peer's own UI would. */
    fun put(packet: PersonPacket, localId: String = "$idPrefix-${nextId++}"): String {
        rows[localId] = packet
        return localId
    }

    /**
     * Model a row deleted between binding and reading — a delete racing a sync. The candidate is
     * still in the snapshot the binder matched against; the read that follows finds nothing.
     */
    fun vanish(localId: String) {
        vanished += localId
    }

    override fun candidates(): List<PersonBinder.Candidate> =
        rows.map { (id, person) ->
            PersonBinder.Candidate(id, person.personKey, person.name, person.email)
        }

    override fun read(localId: String): PersonPacket? =
        if (localId in vanished) null else rows[localId]

    override fun update(localId: String, packet: PersonPacket) {
        rows[localId] = packet
    }

    override fun create(packet: PersonPacket): String {
        if (refuseCreate) error("bind-only peer: refusing to create ${packet.name}")
        val id = "$idPrefix-${nextId++}"
        rows[id] = packet
        return id
    }

    fun byName(name: String): PersonPacket? = rows.values.firstOrNull { it.name == name }
}
