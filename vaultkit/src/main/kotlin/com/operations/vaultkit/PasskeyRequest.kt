package com.operations.vaultkit

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a site asked for, read out of the JSON it asked in.
 *
 * Android's Credential Manager hands a provider the relying party's own
 * `PublicKeyCredentialCreationOptions` or `PublicKeyCredentialRequestOptions`, verbatim, as the JSON
 * the web API would have taken. So the parsing is the parsing of somebody else's document: tolerant
 * about what it accepts, strict about what it requires, and unwilling to invent a default for
 * anything that decides what a credential is *for*.
 *
 * It is here rather than in `:secrets` because it is a pure function from a string to a decision,
 * and the decisions it makes — which relying party, which credential, whether this authenticator can
 * serve the request at all — are the ones worth having tests for.
 */
object PasskeyRequest {

    /** A registration request. */
    data class Creation(
        val rpId: String,
        val rpName: String,
        val userHandle: String,
        val userName: String,
        val userDisplayName: String,
        val challenge: String,
        /** Does `pubKeyCredParams` include ES256? If not, this authenticator has nothing to offer. */
        val supportsEs256: Boolean,
        /** Credentials the site already has; a second one for the same account is not wanted. */
        val excludeCredentialIds: List<String> = emptyList()
    )

    /** A sign-in request. */
    data class Assertion(
        val rpId: String,
        val challenge: String,
        /**
         * The credentials the site will accept, or empty for "any you hold for this relying party"
         * — which is the discoverable-credential flow, and the one passkeys are usually used in.
         */
        val allowCredentialIds: List<String> = emptyList()
    )

    fun parseCreation(json: String): Creation? {
        val root = objectOf(json) ?: return null
        val rp = root.getAsJsonObject("rp")
        // No relying party id, no credential: it is what the signature is scoped to and what decides
        // which site may ever be offered this key. Guessing it would be guessing at who a password
        // belongs to.
        val rpId = rp?.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val challenge = root.string("challenge")?.takeIf { it.isNotBlank() } ?: return null
        val user = root.getAsJsonObject("user")

        val algorithms = root.getAsJsonArray("pubKeyCredParams")
            ?.mapNotNull { it.asJsonObjectOrNull()?.long("alg") }
            .orEmpty()

        return Creation(
            rpId = rpId,
            rpName = rp.string("name").orEmpty(),
            userHandle = user?.string("id").orEmpty(),
            userName = user?.string("name").orEmpty(),
            userDisplayName = user?.string("displayName").orEmpty(),
            challenge = challenge,
            // An absent or empty list means the site stated no preference, and every relying party
            // that states none accepts ES256 — it is the algorithm passkeys are built on. An list
            // that states a preference and leaves ES256 out is a site this authenticator cannot
            // serve, and says so rather than issuing a key that will be rejected at first use.
            supportsEs256 = algorithms.isEmpty() || algorithms.contains(WebAuthn.ALG_ES256),
            excludeCredentialIds = root.credentialIds("excludeCredentials")
        )
    }

    fun parseAssertion(json: String): Assertion? {
        val root = objectOf(json) ?: return null
        val rpId = root.string("rpId")?.takeIf { it.isNotBlank() } ?: return null
        val challenge = root.string("challenge")?.takeIf { it.isNotBlank() } ?: return null
        return Assertion(
            rpId = rpId,
            challenge = challenge,
            allowCredentialIds = root.credentialIds("allowCredentials")
        )
    }

    /**
     * Two credential ids as the same value, whatever encoding each arrived in.
     *
     * A site's `allowCredentials` echoes back an id this app issued, through that site's own
     * library, which may have added padding or used the standard base64 alphabet on the way. Both
     * decode to the same sixteen bytes, and comparing the strings rather than the bytes would mean
     * failing to find a credential over a punctuation mark.
     */
    fun sameCredential(a: String, b: String): Boolean {
        val left = WebAuthn.fromBase64Url(a) ?: return false
        val right = WebAuthn.fromBase64Url(b) ?: return false
        return left.contentEquals(right)
    }

    private fun objectOf(json: String): JsonObject? =
        runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    private fun JsonObject.credentialIds(field: String): List<String> =
        getAsJsonArray(field)
            ?.mapNotNull { it.asJsonObjectOrNull()?.string("id") }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun JsonObject.string(field: String): String? =
        runCatching { get(field)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()

    private fun JsonObject.long(field: String): Long? =
        runCatching { get(field)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull()

    private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { asJsonObject }.getOrNull()
}
