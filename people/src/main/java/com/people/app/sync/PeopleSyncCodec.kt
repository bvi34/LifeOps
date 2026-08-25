package com.people.app.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * The wire format, written by hand against Gson's tree model rather than reflected off the data
 * classes — the same choice `:core`'s `SyncCodec` makes, for the same reason: a field renamed in
 * Kotlin should be a deliberate change to the wire, not a silent one that quietly stops parsing
 * envelopes the other peer has already written to disk.
 *
 * Unknown keys are ignored and missing ones fall back to their defaults, so a newer peer can add a
 * field without the older one choking on the folder.
 */
object PeopleSyncCodec {

    fun encode(envelope: PeerEnvelope): String {
        val root = JsonObject()
        root.addProperty("peer", envelope.peer)

        val acks = JsonObject()
        envelope.acks.forEach { (peer, version) -> acks.addProperty(peer, version) }
        root.add("acks", acks)

        val packets = JsonArray()
        envelope.packets.sortedBy { it.version }.forEach { item ->
            val packet = JsonObject()
            packet.addProperty("version", item.version)
            packet.add("person", encodePerson(item.payload))
            packets.add(packet)
        }
        root.add("packets", packets)
        return root.toString()
    }

    fun decode(text: String): PeerEnvelope? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        val acks = root.getAsJsonObject("acks")?.entrySet()
            ?.associate { (key, value) -> key to value.asLong }
            .orEmpty()
        val packets = root.getAsJsonArray("packets")?.map { element ->
            val obj = element.asJsonObject
            VersionedPacket(
                version = obj.get("version").asLong,
                payload = decodePerson(obj.getAsJsonObject("person"))
            )
        }.orEmpty()
        PeerEnvelope(peer = root.get("peer").asString, packets = packets, acks = acks)
    }.getOrNull()

    private fun encodePerson(person: PersonPacket): JsonObject = JsonObject().apply {
        addProperty("key", person.personKey)
        addProperty("name", person.name)
        person.relationship?.let { addProperty("relationship", it) }
        person.birthDate?.let { addProperty("birthDate", it) }
        person.email?.let { addProperty("email", it) }
        person.phone?.let { addProperty("phone", it) }
        person.note?.let { addProperty("note", it) }
        addProperty("archived", person.archived)
        addProperty("updatedAt", person.updatedAt)
        if (person.deleted) addProperty("deleted", true)
    }

    private fun decodePerson(obj: JsonObject): PersonPacket = PersonPacket(
        personKey = obj.get("key").asString,
        name = obj.get("name")?.asString.orEmpty(),
        relationship = obj.optString("relationship"),
        birthDate = obj.optString("birthDate"),
        email = obj.optString("email"),
        phone = obj.optString("phone"),
        note = obj.optString("note"),
        archived = obj.get("archived")?.asBoolean ?: false,
        updatedAt = obj.get("updatedAt")?.asLong ?: 0L,
        deleted = obj.get("deleted")?.asBoolean ?: false
    )

    private fun JsonObject.optString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}

/**
 * The transport: one JSON file per peer in a folder every peer can see.
 *
 * The Citation seam uses a fixed outbound/inbound pair because that seam has a fixed sender and
 * receiver. This one is symmetric — each peer writes `<peer>-people.json` and reads the others' —
 * so adding a third peer is adding a file, not amending a protocol. Being plain `java.io`, the whole
 * round is JVM-testable against a temp directory, exactly like `:core`'s transport tests.
 */
class PeopleEnvelopeStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun write(envelope: PeerEnvelope) {
        // Write-then-rename: a reader must never catch a half-written envelope, and a crash
        // mid-write must leave the previous round's file intact rather than a truncated one.
        val target = fileFor(envelope.peer)
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(PeopleSyncCodec.encode(envelope))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    fun read(peer: String): PeerEnvelope? {
        val file = fileFor(peer)
        if (!file.exists()) return null
        return PeopleSyncCodec.decode(file.readText())
    }

    private fun fileFor(peer: String) = File(dir, "$peer-people.json")
}
