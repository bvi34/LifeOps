package com.operations.vaultkit

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * One passkey, as it is kept in the vault.
 *
 * ## Why a password manager is the right place for the private key
 *
 * A passkey is a key pair. The site keeps the public half; the private half is the credential, and
 * where it lives decides what happens on the day the phone does not come back. A platform passkey
 * lives in the phone's hardware-backed keystore — excellent protection, and the same hardware
 * binding this whole module exists to work around: the key cannot leave, so it dies with the device
 * unless somebody else's cloud is holding a copy.
 *
 * Here it lives in the sealed document, under a passphrase that is in somebody's head rather than in
 * a TEE, in a file that rides the sandbox archive. That is the same bargain the rest of this vault
 * makes and it is an unusually good fit for passkeys, because a passkey has no "forgot password"
 * link behind it — losing one means an account recovery flow, per site, with support.
 *
 * ## Everything is base64url text, and that is on purpose
 *
 * The document is JSON in a sealed body and these fields are bytes. Storing them as base64url
 * strings rather than as arrays keeps the document small, keeps it readable by a person who has had
 * to open their vault by hand during a recovery, and — the practical one — keeps [VaultItem] a data
 * class whose `equals` means something. A `ByteArray` field would compare by identity, and this
 * item is compared on every merge, every save and every `remember` key in the UI.
 */
data class VaultPasskey(
    /** The credential id the site knows this by, base64url. */
    val credentialId: String,
    /** The relying party: `bank.com`. What the signature is scoped to. */
    val rpId: String,
    val rpName: String = "",
    /** The site's own opaque handle for the account, base64url. Returned on every assertion. */
    val userHandle: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    /** PKCS#8, base64url. The credential itself. */
    val privateKey: String = "",
    /** X.509 SubjectPublicKeyInfo, base64url. */
    val publicKey: String = "",
    /** COSE algorithm. Only ES256 is issued; the field exists so a future one is not a migration. */
    val algorithm: Long = WebAuthn.ALG_ES256,
    val createdAt: Long = 0L
) {

    /** Everything needed to sign is present and the algorithm is one this build issues. */
    val isUsable: Boolean
        get() = credentialId.isNotBlank() &&
            rpId.isNotBlank() &&
            privateKey.isNotBlank() &&
            publicKey.isNotBlank() &&
            algorithm == WebAuthn.ALG_ES256

    /** `me@example.com · bank.com`, for a list that has to be read at a glance. */
    val label: String
        get() = listOf(userName.ifBlank { userDisplayName }, rpId)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/**
 * Making a passkey, and signing with one.
 *
 * The two halves of WebAuthn an authenticator performs, over the structures in [WebAuthn]. Nothing
 * here touches Android, so the tests beside it do the thing that actually proves this works: build a
 * registration, produce an assertion against it, and verify the signature with the public key the
 * registration handed over — which is the same check a relying party's server runs.
 */
object Passkeys {

    /** What a registration produced: the credential to file, and the JSON to hand back. */
    data class Registration(val passkey: VaultPasskey, val responseJson: String)

    /**
     * Create a credential for [options] and produce the registration response.
     *
     * Returns null when the site will not take what this authenticator can issue — in practice, a
     * `pubKeyCredParams` list with no ES256 in it. Refusing is the right answer: issuing a key of an
     * algorithm the site did not ask for produces a credential it rejects at first use, which the
     * household discovers much later and cannot diagnose.
     */
    fun register(
        options: PasskeyRequest.Creation,
        clientDataJson: String?,
        clientDataHash: ByteArray,
        now: Long,
        random: SecureRandom = SecureRandom()
    ): Registration? {
        if (!options.supportsEs256) return null

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()

        // Sixteen random bytes. The spec allows up to 1023 and says only that it must be
        // unpredictable; sixteen is what platform authenticators use and is well past collision.
        val credentialId = ByteArray(16).also(random::nextBytes)

        val public = pair.public as ECPublicKey
        val cose = WebAuthn.coseKeyEs256(
            x = WebAuthn.fixedLength(public.w.affineX, 32),
            y = WebAuthn.fixedLength(public.w.affineY, 32)
        )

        val passkey = VaultPasskey(
            credentialId = WebAuthn.base64Url(credentialId),
            rpId = options.rpId,
            rpName = options.rpName,
            userHandle = options.userHandle,
            userName = options.userName,
            userDisplayName = options.userDisplayName,
            privateKey = WebAuthn.base64Url(pair.private.encoded),
            publicKey = WebAuthn.base64Url(pair.public.encoded),
            createdAt = now
        )

        val authData = WebAuthn.authenticatorData(
            rpId = options.rpId,
            flags = WebAuthn.FLAG_USER_PRESENT or
                WebAuthn.FLAG_USER_VERIFIED or
                WebAuthn.FLAG_BACKUP_ELIGIBLE or
                WebAuthn.FLAG_BACKED_UP or
                WebAuthn.FLAG_ATTESTED_CREDENTIAL_DATA,
            signCount = SIGN_COUNT,
            attestedCredentialData = WebAuthn.attestedCredentialData(credentialId, cose)
        )

        val response = buildString {
            append("{\"id\":\"").append(passkey.credentialId)
            append("\",\"rawId\":\"").append(passkey.credentialId)
            append("\",\"type\":\"public-key\"")
            append(",\"authenticatorAttachment\":\"platform\"")
            append(",\"clientExtensionResults\":{}")
            append(",\"response\":{")
            append("\"clientDataJSON\":\"")
                .append(clientDataJson?.let { WebAuthn.base64Url(it.toByteArray(Charsets.UTF_8)) }.orEmpty())
            append("\",\"attestationObject\":\"")
                .append(WebAuthn.base64Url(WebAuthn.attestationObject(authData)))
            // "internal" because the key never leaves this device in use, and "hybrid" because the
            // vault travels — both are true and a site uses them only to word its prompts.
            append("\",\"transports\":[\"internal\",\"hybrid\"]")
            append("}}")
        }

        // The hash is what a privileged caller supplies in place of client data; it is not used at
        // registration beyond being the thing a relying party will re-derive, so it is accepted and
        // deliberately unused here rather than silently ignored at the call site.
        check(clientDataHash.isNotEmpty() || clientDataJson != null) {
            "a registration needs either client data or its hash"
        }

        return Registration(passkey, response)
    }

    /**
     * Sign a challenge with [passkey] and produce the authentication response.
     *
     * [clientDataHash] is what gets signed alongside the authenticator data: normally SHA-256 of the
     * client data this app built, and for a privileged caller — a browser the platform vouches for —
     * a hash the platform supplied for client data this app never sees. Both end up in the same
     * signature, which is why the parameter is the hash rather than the JSON.
     */
    fun assertion(
        passkey: VaultPasskey,
        clientDataJson: String?,
        clientDataHash: ByteArray
    ): String? {
        if (!passkey.isUsable) return null
        val key = privateKeyOf(passkey) ?: return null

        val authData = WebAuthn.authenticatorData(
            rpId = passkey.rpId,
            flags = WebAuthn.FLAG_USER_PRESENT or
                WebAuthn.FLAG_USER_VERIFIED or
                WebAuthn.FLAG_BACKUP_ELIGIBLE or
                WebAuthn.FLAG_BACKED_UP,
            signCount = SIGN_COUNT
        )

        val signature = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key)
                update(authData)
                update(clientDataHash)
                sign()
            }
        }.getOrNull() ?: return null

        return buildString {
            append("{\"id\":\"").append(passkey.credentialId)
            append("\",\"rawId\":\"").append(passkey.credentialId)
            append("\",\"type\":\"public-key\"")
            append(",\"authenticatorAttachment\":\"platform\"")
            append(",\"clientExtensionResults\":{}")
            append(",\"response\":{")
            append("\"clientDataJSON\":\"")
                .append(clientDataJson?.let { WebAuthn.base64Url(it.toByteArray(Charsets.UTF_8)) }.orEmpty())
            append("\",\"authenticatorData\":\"").append(WebAuthn.base64Url(authData))
            append("\",\"signature\":\"").append(WebAuthn.base64Url(signature))
            append("\",\"userHandle\":\"").append(passkey.userHandle)
            append("\"}}")
        }
    }

    /**
     * The public half of a P-256 private key, as the X.509 bytes a [VaultPasskey] stores.
     *
     * ## Why this has to be computed rather than read
     *
     * A passkey arriving through Credential Exchange ([CredentialExchange]) carries its private key
     * and *not* its public one — correctly, since the public half is a function of the private half
     * and a format that carried both could carry two that disagree. This app needs it anyway: the
     * public key is what a relying party is handed at registration and what [publicKeyOf] returns
     * for every later check, so a credential imported without one would be a credential that cannot
     * be verified against itself.
     *
     * The JDK will not do this step. `KeyFactory` parses a PKCS#8 EC key into a scalar and a curve
     * and exposes no way to multiply the generator by it — the one operation needed — so it is done
     * here, in about thirty lines of BigInteger arithmetic over the prime field.
     *
     * That sounds like the sort of thing nobody should write twice, and the test beside it is the
     * reason it is safe to have written once: it generates real key pairs with the platform's own
     * generator, throws away the public half, recomputes it from the private half, and asserts the
     * bytes come back identical. A mistake in this arithmetic does not produce a subtly wrong key,
     * it produces a different point, and that test sees it every time.
     *
     * Null for anything that is not a P-256 private key, which is the only curve this app issues or
     * accepts — see the ES256 note in [Passkeys].
     */
    fun publicKeyFrom(pkcs8: ByteArray): ByteArray? = runCatching {
        val factory = KeyFactory.getInstance("EC")
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as? ECPrivateKey
            ?: return null
        val parameters = private.params
        val field = parameters.curve.field as? java.security.spec.ECFieldFp ?: return null
        // P-256 and nothing else: 256 bits of prime field, and the group order to match.
        if (field.fieldSize != 256 || parameters.order.bitLength() != 256) return null

        val point = multiply(parameters.generator, private.s, field.p, parameters.curve.a)
        if (point == java.security.spec.ECPoint.POINT_INFINITY) return null
        factory.generatePublic(java.security.spec.ECPublicKeySpec(point, parameters)).encoded
    }.getOrNull()

    /** [generator] added to itself [scalar] times: double-and-add, most significant bit last. */
    private fun multiply(
        generator: java.security.spec.ECPoint,
        scalar: java.math.BigInteger,
        prime: java.math.BigInteger,
        a: java.math.BigInteger
    ): java.security.spec.ECPoint {
        var result = java.security.spec.ECPoint.POINT_INFINITY
        var addend = generator
        var remaining = scalar
        while (remaining.signum() > 0) {
            if (remaining.testBit(0)) result = add(result, addend, prime, a)
            addend = add(addend, addend, prime, a)
            remaining = remaining.shiftRight(1)
        }
        return result
    }

    /**
     * The group law on a short Weierstrass curve, doubling included.
     *
     * Deliberately the textbook version rather than a constant-time one. It runs on a key the
     * household already holds, on a phone, once per imported passkey — there is no attacker
     * measuring it, and a clever implementation here would be a clever implementation nobody in
     * this project could check.
     */
    private fun add(
        left: java.security.spec.ECPoint,
        right: java.security.spec.ECPoint,
        prime: java.math.BigInteger,
        a: java.math.BigInteger
    ): java.security.spec.ECPoint {
        if (left == java.security.spec.ECPoint.POINT_INFINITY) return right
        if (right == java.security.spec.ECPoint.POINT_INFINITY) return left

        val x1 = left.affineX
        val y1 = left.affineY
        val x2 = right.affineX
        val y2 = right.affineY

        // A point plus its own negation is the point at infinity — including doubling one whose y
        // is zero, which the tangent case below would divide by.
        if (x1 == x2 && (y1 + y2).mod(prime).signum() == 0) {
            return java.security.spec.ECPoint.POINT_INFINITY
        }

        val three = java.math.BigInteger.valueOf(3)
        val slope = if (left == right) {
            (three * x1 * x1 + a) * (java.math.BigInteger.TWO * y1).modInverse(prime)
        } else {
            (y2 - y1) * (x2 - x1).modInverse(prime)
        }.mod(prime)

        val x3 = (slope * slope - x1 - x2).mod(prime)
        val y3 = (slope * (x1 - x3) - y1).mod(prime)
        return java.security.spec.ECPoint(x3, y3)
    }

    /** The public half, for a test or for a check that a stored credential is still coherent. */
    fun publicKeyOf(passkey: VaultPasskey): java.security.PublicKey? =
        WebAuthn.fromBase64Url(passkey.publicKey)?.let { encoded ->
            runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)) }
                .getOrNull()
        }

    private fun privateKeyOf(passkey: VaultPasskey): PrivateKey? =
        WebAuthn.fromBase64Url(passkey.privateKey)?.let { encoded ->
            runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded)) }
                .getOrNull()
        }

    /**
     * Always zero, and this is a decision rather than an omission.
     *
     * The signature counter exists so a relying party can notice a *cloned* authenticator: a counter
     * that goes backwards means two copies of a key that was supposed to exist once. A credential
     * held in a vault that deliberately travels in a backup is a credential that may legitimately be
     * used from two phones, so an incrementing counter here would report a clone every time somebody
     * restored, which is the opposite of useful. The spec's answer for exactly this case is to leave
     * it at zero — a counter that never moves says "this authenticator does not keep one", which is
     * true, rather than saying something false that happens to increase.
     */
    private const val SIGN_COUNT = 0
}
