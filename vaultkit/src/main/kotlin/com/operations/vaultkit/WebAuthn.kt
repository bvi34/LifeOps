package com.operations.vaultkit

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * The byte structures a passkey is made of.
 *
 * Everything here is from the WebAuthn Level 3 spec and none of it needs a phone, which is the
 * point: a passkey is asymmetric cryptography over some carefully-shaped bytes, and getting the
 * shapes wrong produces a credential that a site rejects for reasons it will never explain. So the
 * shaping happens here, in the pure module, where the tests beside it can build a registration and
 * then verify its own assertion with `java.security.Signature` — which is the same check the relying
 * party's server performs, done on the JVM without an emulator.
 *
 * ES256 only: ECDSA over P-256, COSE algorithm `-7`. It is what every relying party accepts, it is
 * what `java.security` has had for a decade, and it needs no native library — the same test the
 * envelope's key derivation is held to, for the same reason. A passkey that only opens on a build
 * with a particular library is not a credential a household can carry to their next phone.
 */
object WebAuthn {

    /** COSE algorithm identifier for ECDSA with SHA-256 over P-256. */
    const val ALG_ES256 = -7L

    // Authenticator data flag bits. See the notes on [authenticatorData] for why four of these are
    // always set here.
    const val FLAG_USER_PRESENT = 0x01
    const val FLAG_USER_VERIFIED = 0x04
    const val FLAG_BACKUP_ELIGIBLE = 0x08
    const val FLAG_BACKED_UP = 0x10
    const val FLAG_ATTESTED_CREDENTIAL_DATA = 0x40

    /**
     * All zeroes, and deliberately.
     *
     * The AAGUID identifies the *make and model* of an authenticator, and a relying party can use it
     * to decide whose authenticators it will accept. Vendors register one. This app has not, so the
     * honest value is the one the spec reserves for "not saying" — inventing sixteen bytes would be
     * claiming an identity nobody issued, and picking another vendor's would be worse.
     */
    val AAGUID_NONE = ByteArray(16)

    /**
     * The authenticator data: what was signed, other than the challenge.
     *
     * ```
     *   rpIdHash        SHA-256 of the relying party id    32 bytes
     *   flags                                               1 byte
     *   signCount                                           4 bytes, big-endian
     *   attestedCredentialData                              present only at registration
     * ```
     *
     * Two of the flags are worth stating because they are a claim about *this* app rather than about
     * this moment:
     *
     *  - **Backup eligible** and **backed up** are both set. They mean the credential is not bound
     *    to one device and a copy exists elsewhere, which is exactly and literally true here: the
     *    private key lives in the vault, the vault rides in the sandbox archive, and the whole thesis
     *    of this module is that what it holds survives the phone. A site reads those bits to decide
     *    whether to keep offering a password as a fallback, and lying either way would make its
     *    decision wrong.
     *  - **User verified** is set because the vault was unlocked to get here — a passphrase, or the
     *    device credential behind the fingerprint shortcut. If it were not unlocked there would be no
     *    private key to sign with, so the bit cannot be set when it is not earned.
     */
    fun authenticatorData(
        rpId: String,
        flags: Int,
        signCount: Int = 0,
        attestedCredentialData: ByteArray? = null
    ): ByteArray = ByteArrayOutputStream().apply {
        write(sha256(rpId.toByteArray(Charsets.UTF_8)))
        write(flags)
        write(byteArrayOf(
            (signCount ushr 24).toByte(),
            (signCount ushr 16).toByte(),
            (signCount ushr 8).toByte(),
            signCount.toByte()
        ))
        attestedCredentialData?.let(::write)
    }.toByteArray()

    /**
     * The credential's own description, appended to the authenticator data at registration.
     *
     * ```
     *   aaguid                        16 bytes
     *   credentialIdLength             2 bytes, big-endian
     *   credentialId
     *   credentialPublicKey            COSE key, CBOR
     * ```
     */
    fun attestedCredentialData(
        credentialId: ByteArray,
        coseKey: ByteArray,
        aaguid: ByteArray = AAGUID_NONE
    ): ByteArray = ByteArrayOutputStream().apply {
        write(aaguid)
        write(byteArrayOf((credentialId.size ushr 8).toByte(), credentialId.size.toByte()))
        write(credentialId)
        write(coseKey)
    }.toByteArray()

    /**
     * The public key, as COSE_Key (RFC 8152).
     *
     * The labels are negative and out of order on purpose — that is how the spec numbers them, and
     * the order below is the one the WebAuthn registry gives:
     *
     * ```
     *    1 (kty)  : 2   EC2
     *    3 (alg)  : -7  ES256
     *   -1 (crv)  : 1   P-256
     *   -2 (x)    : 32 bytes
     *   -3 (y)    : 32 bytes
     * ```
     *
     * [x] and [y] must each be exactly 32 bytes, left-padded. A coordinate whose leading byte
     * happens to be zero is a 31-byte `BigInteger` and a rejected credential, which is the kind of
     * bug that appears one time in two hundred and fifty-six.
     */
    fun coseKeyEs256(x: ByteArray, y: ByteArray): ByteArray {
        require(x.size == 32) { "EC x coordinate must be 32 bytes, was ${x.size}" }
        require(y.size == 32) { "EC y coordinate must be 32 bytes, was ${y.size}" }
        return Cbor.map(
            listOf(
                Cbor.int(1) to Cbor.int(2),
                Cbor.int(3) to Cbor.int(ALG_ES256),
                Cbor.int(-1) to Cbor.int(1),
                Cbor.int(-2) to Cbor.bytes(x),
                Cbor.int(-3) to Cbor.bytes(y)
            )
        )
    }

    /**
     * The attestation object, with no attestation in it.
     *
     * ```
     *   { "fmt": "none", "attStmt": {}, "authData": <bytes> }
     * ```
     *
     * `none` is the right answer rather than a shortcut. Attestation is a signed claim about which
     * hardware holds the key, made by a certificate the manufacturer issued; a vault whose entire
     * purpose is that the key is *not* bound to hardware has nothing true to say in that field, and
     * every self-signed alternative amounts to asserting your own trustworthiness. Relying parties
     * accept `none` for passkeys — it is what platform authenticators send for ordinary consumer
     * sign-ins.
     */
    fun attestationObject(authData: ByteArray): ByteArray =
        Cbor.map(
            listOf(
                Cbor.text("fmt") to Cbor.text("none"),
                Cbor.text("attStmt") to Cbor.map(emptyList()),
                Cbor.text("authData") to Cbor.bytes(authData)
            )
        )

    /**
     * The client data, as the JSON that gets hashed and signed.
     *
     * Built by hand rather than by a serialiser because the bytes matter: what the relying party
     * verifies is a hash of *these exact bytes*, echoed back through the response, so a library that
     * reordered keys or changed spacing between two runs would break nothing visibly and everything
     * eventually. The field order is the one the spec gives.
     */
    fun clientDataJson(
        type: String,
        challenge: String,
        origin: String,
        crossOrigin: Boolean = false
    ): String = buildString {
        append("{\"type\":\"").append(escape(type))
        append("\",\"challenge\":\"").append(escape(challenge))
        append("\",\"origin\":\"").append(escape(origin))
        append("\",\"crossOrigin\":").append(crossOrigin)
        append("}")
    }

    /**
     * The origin an Android app is known by when it is not a browser.
     *
     * A web sign-in has an origin like `https://bank.com`. A native app has no URL, so WebAuthn on
     * Android names it by the hash of the certificate its APK was signed with —
     * `android:apk-key-hash:<base64url(sha256(cert))>`. A relying party matches that against its
     * own `assetlinks.json`, which is what stops an app that merely claims to be the bank from being
     * handed the bank's passkey.
     */
    fun apkOrigin(signingCertificateSha256: ByteArray): String =
        "android:apk-key-hash:" + base64Url(signingCertificateSha256)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Base64url without padding — the only encoding WebAuthn's JSON uses. */
    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * Base64url back to bytes, tolerating padding and the standard alphabet.
     *
     * A relying party's challenge arrives as whatever its own library produced, and while the spec
     * says base64url, plenty of servers send padded base64url and a few send standard base64. All
     * three decode to the same challenge, and rejecting two of them would be rejecting sign-ins over
     * a punctuation mark.
     */
    fun fromBase64Url(value: String): ByteArray? {
        val cleaned = value.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        return runCatching { Base64.getUrlDecoder().decode(cleaned) }.getOrNull()
    }

    /** A [BigInteger] as exactly [length] bytes, left-padded, sign byte dropped. */
    fun fixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == length -> raw
            // BigInteger prepends a zero byte when the top bit would otherwise read as negative.
            raw.size == length + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < length -> ByteArray(length).also { raw.copyInto(it, length - raw.size) }
            else -> throw IllegalArgumentException("value does not fit in $length bytes")
        }
    }

    private fun escape(value: String): String = buildString {
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}
