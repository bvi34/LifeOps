package com.people.app.partner

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * The partner envelope's wire format — hand-written against Gson's tree model, like every other
 * codec in this app, so a Kotlin rename is a deliberate change to the wire rather than a silent one.
 *
 * Unknown keys are ignored and missing ones fall back to their defaults, which matters more here
 * than on the People seam: the peer on the other end of *this* one is a different install that may
 * be a release behind, with no shared build to keep it in step.
 */
object PartnerSyncCodec {

    fun encode(envelope: PartnerEnvelope): String = JsonObject().apply {
        addProperty("wire", PartnerWire.VERSION)
        addProperty("instance", envelope.instanceId)
        addProperty("name", envelope.displayName)
        addProperty("issuedAt", envelope.issuedAt)
        add("shares", JsonArray().apply { envelope.shares.forEach { add(encodeShare(it)) } })
    }.toString()

    fun decode(text: String): PartnerEnvelope? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        PartnerEnvelope(
            instanceId = root.get("instance").asString,
            displayName = root.get("name")?.asString.orEmpty(),
            issuedAt = root.get("issuedAt")?.asLong ?: 0L,
            shares = root.getAsJsonArray("shares")?.map { decodeShare(it.asJsonObject) }.orEmpty()
        )
    }.getOrNull()

    private fun encodeShare(share: PartnerShare): JsonObject = JsonObject().apply {
        addProperty("to", share.partnerInstanceId)
        addProperty("token", share.token)
        addProperty("weekStart", share.week.weekStart)
        addProperty("weekEnd", share.week.weekEnd)
        add("tasks", JsonArray().apply { share.week.tasks.forEach { add(encodeTask(it)) } })
        add("adds", JsonArray().apply { share.contributions.forEach { add(encodeContribution(it)) } })
    }

    private fun decodeShare(obj: JsonObject): PartnerShare = PartnerShare(
        partnerInstanceId = obj.get("to").asString,
        token = obj.get("token")?.asString.orEmpty(),
        week = SharedWeek(
            weekStart = obj.get("weekStart")?.asString.orEmpty(),
            weekEnd = obj.get("weekEnd")?.asString.orEmpty(),
            tasks = obj.getAsJsonArray("tasks")?.map { decodeTask(it.asJsonObject) }.orEmpty()
        ),
        contributions = obj.getAsJsonArray("adds")?.map { decodeContribution(it.asJsonObject) }.orEmpty()
    )

    private fun encodeTask(task: SharedTask): JsonObject = JsonObject().apply {
        addProperty("id", task.taskId)
        addProperty("title", task.title)
        task.dueDate?.let { addProperty("due", it) }
        addProperty("done", task.done)
        task.fromContribution?.let { addProperty("from", it) }
    }

    private fun decodeTask(obj: JsonObject): SharedTask = SharedTask(
        taskId = obj.get("id").asString,
        title = obj.get("title")?.asString.orEmpty(),
        dueDate = obj.optString("due"),
        done = obj.get("done")?.asBoolean ?: false,
        fromContribution = obj.optString("from")
    )

    private fun encodeContribution(add: Contribution): JsonObject = JsonObject().apply {
        addProperty("id", add.id)
        addProperty("title", add.title)
        add.dueDate?.let { addProperty("due", it) }
        addProperty("createdAt", add.createdAt)
    }

    private fun decodeContribution(obj: JsonObject): Contribution = Contribution(
        id = obj.get("id").asString,
        title = obj.get("title")?.asString.orEmpty(),
        dueDate = obj.optString("due"),
        createdAt = obj.get("createdAt")?.asLong ?: 0L
    )

    private fun JsonObject.optString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}

/**
 * The transport: one JSON file per instance in a folder both instances can see.
 *
 * The same shape as [com.people.app.sync.PeopleEnvelopeStore], for the same reasons — no server,
 * works offline, and the whole round is JVM-testable against a temp directory. What differs is who
 * is on the other end. The People seam's folder is `filesDir`, shared by apps in one process; a
 * partner is on somebody else's device, so this folder is an *exchange point* — a directory the two
 * households keep in step by whatever they already use for that, or a file handed across once.
 * Nothing above this class knows which, and that is the point of drawing the seam at a folder: the
 * pairing, the mirror and the contributions are settled and testable without deciding how the bytes
 * travel.
 */
class PartnerEnvelopeStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun write(envelope: PartnerEnvelope) {
        // Write-then-rename: a reader must never catch a half-written envelope, and a crash mid-write
        // must leave the previous round's file intact rather than a truncated one.
        val target = fileFor(envelope.instanceId)
        val temp = File(dir, "${target.name}.tmp")
        temp.writeText(PartnerSyncCodec.encode(envelope))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    fun read(instanceId: String): PartnerEnvelope? {
        val file = fileFor(instanceId)
        if (!file.exists()) return null
        return PartnerSyncCodec.decode(file.readText())
    }

    /** Drop a partner's envelope when the link goes — an unpaired partner's week is not ours to keep. */
    fun forget(instanceId: String) {
        fileFor(instanceId).delete()
    }

    private fun fileFor(instanceId: String) = File(dir, "${instanceId.sanitised()}-partner.json")

    /**
     * An instance id arrives from a scanned code, and a scanned code is somebody else's bytes. It is
     * used here to name a file, so it is confined to characters that cannot walk out of the folder.
     */
    private fun String.sanitised(): String =
        map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}
