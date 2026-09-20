package com.utilities.app.messages.seal

/**
 * A session, as text on disk.
 *
 * A hand-written format rather than JSON, for one reason that matters: every field is a byte array,
 * JSON has no way to hold one, and a codec that base64s each field into an object is the same work
 * with a parser in the way. One field per line, `name=base64`, with the skipped keys at the end.
 * Anything that will not parse gives way to no session at all, which costs one round trip to
 * re-establish and never costs a wrongly-restored ratchet.
 */
object SessionCodec {

    private const val VERSION = "v1"

    fun encode(snapshot: Session.Snapshot): String = buildString {
        appendLine(VERSION)
        appendLine("peer=${Bytes.encode(snapshot.peerIdentityKey)}")
        snapshot.openingEphemeral?.let { appendLine("opening=${Bytes.encode(it)}") }
        val ratchet = snapshot.ratchet
        appendLine("root=${Bytes.encode(ratchet.rootKey)}")
        appendLine("sendpriv=${Bytes.encode(ratchet.sendingPrivateKey)}")
        appendLine("sendpub=${Bytes.encode(ratchet.sendingPublicKey)}")
        ratchet.receivingPublicKey?.let { appendLine("recvpub=${Bytes.encode(it)}") }
        ratchet.sendingChain?.let { appendLine("sendchain=${Bytes.encode(it)}") }
        ratchet.receivingChain?.let { appendLine("recvchain=${Bytes.encode(it)}") }
        appendLine("sent=${ratchet.sent}")
        appendLine("received=${ratchet.received}")
        appendLine("previous=${ratchet.previousChainLength}")
        ratchet.skipped.forEach { (key, value) ->
            appendLine("skip=${key.ratchetKey}:${key.number}:${Bytes.encode(value)}")
        }
    }

    fun decode(text: String): Session.Snapshot {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.firstOrNull() != VERSION) throw SealException("a session written by another version")

        val fields = HashMap<String, String>()
        val skipped = LinkedHashMap<Ratchet.SkippedKey, ByteArray>()
        lines.drop(1).forEach { line ->
            val at = line.indexOf('=')
            if (at <= 0) return@forEach
            val name = line.substring(0, at)
            val value = line.substring(at + 1)
            if (name == "skip") {
                val parts = value.split(":")
                if (parts.size == 3) {
                    val number = parts[1].toIntOrNull() ?: return@forEach
                    skipped[Ratchet.SkippedKey(parts[0], number)] = Bytes.decode(parts[2])
                }
            } else {
                fields[name] = value
            }
        }

        fun bytes(name: String): ByteArray =
            fields[name]?.let { Bytes.decode(it) } ?: throw SealException("a session is missing $name")

        fun optional(name: String): ByteArray? = fields[name]?.let { Bytes.decode(it) }

        fun number(name: String): Int = fields[name]?.toIntOrNull() ?: 0

        return Session.Snapshot(
            peerIdentityKey = bytes("peer"),
            openingEphemeral = optional("opening"),
            ratchet = Ratchet.Snapshot(
                rootKey = bytes("root"),
                sendingPrivateKey = bytes("sendpriv"),
                sendingPublicKey = bytes("sendpub"),
                receivingPublicKey = optional("recvpub"),
                sendingChain = optional("sendchain"),
                receivingChain = optional("recvchain"),
                sent = number("sent"),
                received = number("received"),
                previousChainLength = number("previous"),
                skipped = skipped
            )
        )
    }
}
