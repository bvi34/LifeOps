package com.people.app.partner

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Random

/**
 * The partner seam: two *separate* LifeOps instances — two households, two devices — agreeing to
 * share one week.
 *
 * This is a different thing from the People seam next door, and the distinction is the whole reason
 * it is its own package. The People seam reconciles the apps *inside one install*: every peer is
 * trusted, every peer sees the same folder, and identity is settled by a key both sides invented and
 * then converged on. A partner is none of that. They are somebody else's install, they were never in
 * your directory, and nothing about them can be inferred — so the pairing has to be *asserted* by two
 * people standing next to each other, once, and remembered afterwards.
 *
 * That is what the QR code is for. It is not a transport; it is a **statement of consent** carried
 * out of band, where a person can see who they are pairing with.
 */

/** The wire's identity, so a scanner can tell our code from every other QR code in the world. */
object PartnerWire {

    const val PREFIX = "LIFEOPS-PARTNER"

    /**
     * Bumped only for a change the older side cannot read past. Unknown JSON keys are ignored on
     * decode, so adding a field is not that change.
     */
    const val VERSION = 1
}

/**
 * What one side's QR code says: *this is me, here is how to address me, and here is my half of the
 * secret.*
 *
 * [personKey] is the key the *sender's* directory holds for the person they are pairing with — so
 * the scanner learns how they are filed on the other side. It is context, not identity: it is
 * deliberately never used to bind anything, because the People seam's keys are agreed between apps
 * in one install and mean nothing across a pairing. Carrying "whose row this code came from" costs
 * nothing and is the one piece of provenance that survives if a link ever has to be untangled by
 * hand; inferring anything from it would be a mistake.
 *
 * [secret] is half of a pair. It is deliberately **not** a password: on its own it authorises
 * nothing, because the token that actually gates the seam ([PairSecret.token]) needs *both* halves,
 * and each half only leaves its own device by being shown on a screen to somebody holding a camera.
 * That is what makes the connection two-way by construction — one scan yields one half of a token
 * neither side can complete alone, so a link cannot be established by a peer that was never scanned.
 */
data class PartnerInvite(
    val instanceId: String,
    val displayName: String,
    val personKey: String,
    val secret: String,
    val issuedAt: Long,
    val wire: Int = PartnerWire.VERSION
)

/**
 * The QR payload's text form: `LIFEOPS-PARTNER:1:<base64url JSON>`.
 *
 * Written against Gson's tree model rather than reflected off the data class, for the reason the
 * other codecs in this app give: a field renamed in Kotlin should be a deliberate change to the
 * wire, not a silent one that stops decoding codes people have already photographed.
 *
 * Base64 rather than raw JSON because a code somebody might paste into the manual-entry box should
 * be one opaque token rather than something that looks editable, and because a payload full of
 * braces and quotes reads as a suspicious URL to every other scanner app that happens to see it.
 */
object PartnerInviteCodec {

    fun encode(invite: PartnerInvite): String {
        val json = JsonObject().apply {
            addProperty("i", invite.instanceId)
            addProperty("n", invite.displayName)
            addProperty("k", invite.personKey)
            addProperty("s", invite.secret)
            addProperty("t", invite.issuedAt)
        }
        val body = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
        return "${PartnerWire.PREFIX}:${invite.wire}:$body"
    }

    /**
     * Decode a scanned or pasted code, or null if it is not one of ours.
     *
     * Null rather than an exception because the camera hands us whatever it saw — a wifi code, a
     * URL, a shop's loyalty barcode — and "that is not a pairing code" is an ordinary outcome of
     * pointing a phone at the world, not a failure worth a stack trace.
     */
    fun decode(text: String): PartnerInvite? = runCatching {
        val parts = text.trim().split(':', limit = 3)
        if (parts.size != 3 || !parts[0].equals(PartnerWire.PREFIX, ignoreCase = true)) return null
        val wire = parts[1].toIntOrNull() ?: return null
        // A newer wire is a code this build cannot honour. Say no rather than half-read it: a link
        // built from fields we guessed at is worse than a link the user is told to retry.
        if (wire > PartnerWire.VERSION) return null

        val json = JsonParser.parseString(
            String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8)
        ).asJsonObject

        val invite = PartnerInvite(
            instanceId = json.get("i").asString,
            displayName = json.get("n")?.asString.orEmpty(),
            personKey = json.get("k")?.asString.orEmpty(),
            secret = json.get("s").asString,
            issuedAt = json.get("t")?.asLong ?: 0L,
            wire = wire
        )
        if (invite.instanceId.isBlank() || invite.secret.isBlank()) null else invite
    }.getOrNull()
}

/** The two halves of a pairing, and the token they only make together. */
object PairSecret {

    private const val BYTES = 16

    /** A fresh half-secret for this instance's own code. */
    fun mint(random: Random = SecureRandom()): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * The token that proves *both* people scanned.
     *
     * Symmetric on purpose — the halves are sorted before hashing, so the two devices compute the
     * same token from opposite directions without either having to be the initiator. That symmetry
     * is what lets the handshake finish with no round trip: each side publishes the token it could
     * only compute after scanning, and seeing that token come back in the partner's envelope *is*
     * the confirmation that they scanned you too.
     */
    fun token(mine: String, theirs: String): String {
        val ordered = listOf(mine, theirs).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(ordered.joinToString(" ").toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
