package com.people.app.sync

/**
 * An in-memory [PeerRoster] standing in for one peer's database, so a whole round can be run and
 * asserted on the JVM. Row ids are local and deliberately *not* the person key — that gap is the
 * thing the binder exists to close, so a fake that papered over it would test nothing.
 */
class FakeRoster(private val idPrefix: String) : PeerRoster {

    val rows = LinkedHashMap<String, PersonPacket>()

    /** Rows that are still visible to binding but have gone by the time they are read. */
    private val vanished = LinkedHashMap<String, PersonPacket>()

    private var nextId = 1

    /** Record a locally-authored person, as a peer's own UI would. */
    fun put(packet: PersonPacket, localId: String = "$idPrefix-${nextId++}"): String {
        rows[localId] = packet
        return localId
    }

    /**
     * Model a row deleted between binding and reading — a delete racing a sync.
     *
     * The row stays a *candidate* until something tries to read it, which is the race: the binder
     * matched against a snapshot taken before the delete landed. The read that misses it clears it
     * for good, so the next round sees a roster without it and the binder reaches a different, and
     * now unambiguous, decision — exactly what a real roster backed by a database does.
     */
    fun vanish(localId: String) {
        rows.remove(localId)?.let { vanished[localId] = it }
    }

    override fun candidates(): List<PersonBinder.Candidate> =
        (rows + vanished).map { (id, person) ->
            PersonBinder.Candidate(id, person.personKey, person.name, person.email)
        }

    override fun read(localId: String): PersonPacket? =
        if (vanished.remove(localId) != null) null else rows[localId]

    override fun update(localId: String, packet: PersonPacket) {
        rows[localId] = packet
    }

    /** How many rows the seam has created here — what a creation policy is asserted on. */
    var created = 0
        private set

    override fun create(packet: PersonPacket): String {
        val id = "$idPrefix-${nextId++}"
        rows[id] = packet
        created++
        return id
    }

    fun byName(name: String): PersonPacket? = rows.values.firstOrNull { it.name == name }
}
