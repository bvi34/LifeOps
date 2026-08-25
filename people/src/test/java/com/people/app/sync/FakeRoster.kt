package com.people.app.sync

/**
 * An in-memory [PeerRoster] standing in for one peer's database, so a whole two-peer round can be
 * run and asserted on the JVM. Row ids are local and deliberately *not* the person key — that gap
 * is the thing the binder exists to close, so a fake that papered over it would test nothing.
 */
class FakeRoster(private val idPrefix: String) : PeerRoster {

    val rows = LinkedHashMap<String, PersonPacket>()
    private var nextId = 1

    /** Record a locally-authored person, as a peer's own UI would. */
    fun put(packet: PersonPacket, localId: String = "$idPrefix-${nextId++}"): String {
        rows[localId] = packet
        return localId
    }

    override fun candidates(): List<PersonBinder.Candidate> =
        rows.map { (id, person) ->
            PersonBinder.Candidate(id, person.personKey, person.name, person.email)
        }

    override fun read(localId: String): PersonPacket? = rows[localId]

    override fun update(localId: String, packet: PersonPacket) {
        rows[localId] = packet
    }

    override fun create(packet: PersonPacket): String {
        val id = "$idPrefix-${nextId++}"
        rows[id] = packet
        return id
    }

    fun byName(name: String): PersonPacket? = rows.values.firstOrNull { it.name == name }
}
