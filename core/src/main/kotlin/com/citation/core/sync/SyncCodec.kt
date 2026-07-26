package com.citation.core.sync

import com.citation.core.anchor.TextAnchor
import com.citation.core.key.EntityKey
import com.citation.core.model.SourceType
import com.citation.core.note.Note
import com.citation.core.note.NoteType
import com.citation.core.note.PassageReference
import com.citation.core.note.SourceDescriptor
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The sync **wire codec**: turns packets, intents, and envelopes into JSON and back.
 *
 * The design's requirement is that a packet is **legible without the source** — LifeOps, which never
 * opens a book, must be able to read a note's frozen context straight off the wire. So the codec
 * writes the whole frozen `{sourceType, sourceId, title, author, snapshot, anchor}` explicitly, and
 * a decode round-trips it losslessly. Built on Gson's tree model (not reflection) so the sealed
 * [TextAnchor] and the sum-type [UpPacket] carry explicit type tags and never depend on Gson's
 * polymorphism.
 */
object SyncCodec {

    // --- Up packets ----------------------------------------------------------------------------

    fun encodeUpPacket(packet: UpPacket): String = upPacketJson(packet).toString()

    fun decodeUpPacket(json: String): UpPacket = upPacketFromJson(JsonParser.parseString(json).asJsonObject)

    private fun upPacketJson(packet: UpPacket): JsonObject = when (packet) {
        is TelemetryPacket -> JsonObject().apply {
            addProperty("kind", "telemetry")
            addProperty("bookKey", packet.bookKey?.toString())
            addProperty("title", packet.title)
            addProperty("minutesRead", packet.minutesRead)
            addProperty("occurredAt", packet.occurredAt)
        }
        is NotePacket -> JsonObject().apply {
            addProperty("kind", "note")
            addProperty("bookKey", packet.bookKey?.toString())
            addProperty("sourceType", packet.sourceType.name)
            addProperty("sourceId", packet.sourceId)
            add("note", noteJson(packet.note))
        }
    }

    private fun upPacketFromJson(obj: JsonObject): UpPacket = when (obj.get("kind").asString) {
        "telemetry" -> TelemetryPacket(
            bookKey = obj.entityKey("bookKey"),
            title = obj.get("title").asString,
            minutesRead = obj.get("minutesRead").asInt,
            occurredAt = obj.get("occurredAt").asLong
        )
        "note" -> NotePacket(
            bookKey = obj.entityKey("bookKey"),
            sourceType = SourceType.valueOf(obj.get("sourceType").asString),
            sourceId = obj.stringOrNull("sourceId"),
            note = noteFromJson(obj.getAsJsonObject("note"))
        )
        else -> error("Unknown up-packet kind in $obj")
    }

    // --- Down intents --------------------------------------------------------------------------

    fun encodeIntent(intent: AcquireBookIntent): String = intentJson(intent).toString()

    fun decodeIntent(json: String): AcquireBookIntent = intentFromJson(JsonParser.parseString(json).asJsonObject)

    private fun intentJson(intent: AcquireBookIntent) = JsonObject().apply {
        addProperty("intentKey", intent.intentKey.toString())
        addProperty("title", intent.title)
        addProperty("author", intent.author)
    }

    private fun intentFromJson(obj: JsonObject) = AcquireBookIntent(
        intentKey = EntityKey.parse(obj.get("intentKey").asString)!!,
        title = obj.get("title").asString,
        author = obj.stringOrNull("author")
    )

    // --- Envelopes -----------------------------------------------------------------------------

    fun encodeOutbound(env: OutboundEnvelope): String = JsonObject().apply {
        addProperty("peer", env.peer)
        addProperty("ackedIntentVersion", env.ackedIntentVersion)
        add("packets", JsonArray().apply {
            env.packets.forEach { v ->
                add(JsonObject().apply {
                    addProperty("version", v.version)
                    add("payload", upPacketJson(v.payload))
                })
            }
        })
    }.toString()

    fun decodeOutbound(json: String): OutboundEnvelope {
        val obj = JsonParser.parseString(json).asJsonObject
        val packets = obj.getAsJsonArray("packets").map { el ->
            val o = el.asJsonObject
            Mailbox.Versioned(o.get("version").asLong, upPacketFromJson(o.getAsJsonObject("payload")))
        }
        return OutboundEnvelope(obj.get("peer").asString, packets, obj.get("ackedIntentVersion").asLong)
    }

    fun encodeInbound(env: InboundEnvelope): String = JsonObject().apply {
        addProperty("ackedPacketVersion", env.ackedPacketVersion)
        add("intents", JsonArray().apply {
            env.intents.forEach { v ->
                add(JsonObject().apply {
                    addProperty("version", v.version)
                    add("payload", intentJson(v.payload))
                })
            }
        })
    }.toString()

    fun decodeInbound(json: String): InboundEnvelope {
        val obj = JsonParser.parseString(json).asJsonObject
        val intents = obj.getAsJsonArray("intents").map { el ->
            val o = el.asJsonObject
            Mailbox.Versioned(o.get("version").asLong, intentFromJson(o.getAsJsonObject("payload")))
        }
        return InboundEnvelope(intents, obj.get("ackedPacketVersion").asLong)
    }

    // --- Note / anchor -------------------------------------------------------------------------

    private fun noteJson(note: Note): JsonObject = JsonObject().apply {
        addProperty("key", note.key.toString())
        addProperty("type", note.type.name)
        addProperty("body", note.body)
        add("source", descriptorJson(note.source))
        addProperty("createdAt", note.createdAt)
        add("references", JsonArray().apply {
            note.references.forEach { add(referenceJson(it)) }
        })
    }

    private fun noteFromJson(obj: JsonObject): Note = Note(
        key = EntityKey.parse(obj.get("key").asString)!!,
        type = NoteType.valueOf(obj.get("type").asString),
        body = obj.get("body").asString,
        source = descriptorFromJson(obj.getAsJsonObject("source")),
        references = obj.getAsJsonArray("references").map { referenceFromJson(it.asJsonObject) },
        createdAt = obj.get("createdAt").asLong
    )

    private fun descriptorJson(d: SourceDescriptor) = JsonObject().apply {
        addProperty("bookKey", d.bookKey?.toString())
        addProperty("sourceType", d.sourceType.name)
        addProperty("sourceId", d.sourceId)
        addProperty("title", d.title)
        addProperty("author", d.author)
    }

    private fun descriptorFromJson(obj: JsonObject) = SourceDescriptor(
        bookKey = obj.entityKey("bookKey"),
        sourceType = SourceType.valueOf(obj.get("sourceType").asString),
        sourceId = obj.stringOrNull("sourceId"),
        title = obj.get("title").asString,
        author = obj.stringOrNull("author")
    )

    private fun referenceJson(ref: PassageReference) = JsonObject().apply {
        addProperty("snapshot", ref.quotedSnapshot)
        add("anchor", anchorJson(ref.anchor))
    }

    private fun referenceFromJson(obj: JsonObject) = PassageReference(
        quotedSnapshot = obj.get("snapshot").asString,
        anchor = anchorFromJson(obj.getAsJsonObject("anchor"))
    )

    private fun anchorJson(anchor: TextAnchor): JsonObject = when (anchor) {
        is TextAnchor.Flowing -> JsonObject().apply {
            addProperty("type", "flowing")
            addProperty("chapterOrdinal", anchor.chapterOrdinal)
            addProperty("approxStart", anchor.approxStart)
            addProperty("quote", anchor.quote)
            addProperty("prefix", anchor.prefix)
            addProperty("suffix", anchor.suffix)
        }
        is TextAnchor.Pdf -> JsonObject().apply {
            addProperty("type", "pdf")
            addProperty("page", anchor.page)
            addProperty("quote", anchor.quote)
            add("quads", JsonArray().apply {
                anchor.quads.forEach { q ->
                    add(JsonObject().apply {
                        addProperty("x0", q.x0); addProperty("y0", q.y0)
                        addProperty("x1", q.x1); addProperty("y1", q.y1)
                    })
                }
            })
        }
    }

    private fun anchorFromJson(obj: JsonObject): TextAnchor = when (obj.get("type").asString) {
        "flowing" -> TextAnchor.Flowing(
            chapterOrdinal = obj.get("chapterOrdinal").asInt,
            approxStart = obj.get("approxStart").asInt,
            quote = obj.get("quote").asString,
            prefix = obj.stringOrNull("prefix") ?: "",
            suffix = obj.stringOrNull("suffix") ?: ""
        )
        "pdf" -> TextAnchor.Pdf(
            page = obj.get("page").asInt,
            quads = obj.getAsJsonArray("quads").map {
                val q = it.asJsonObject
                TextAnchor.Quad(
                    q.get("x0").asFloat, q.get("y0").asFloat, q.get("x1").asFloat, q.get("y1").asFloat
                )
            },
            quote = obj.get("quote").asString
        )
        else -> error("Unknown anchor type in $obj")
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.entityKey(name: String): EntityKey? =
        stringOrNull(name)?.let { EntityKey.parse(it) }
}
