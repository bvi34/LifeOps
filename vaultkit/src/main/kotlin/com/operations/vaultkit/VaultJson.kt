package com.operations.vaultkit

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * The document's wire form: JSON, inside the sealed body and nowhere else.
 *
 * Two decisions worth stating.
 *
 * **It is not pretty-printed.** Everywhere else in the suite a stored document is formatted so a
 * person can open it in an editor — the backup manifest says as much. Here nobody can open it
 * without the passphrase, so the whitespace would buy nothing and the extra bytes are extra
 * plaintext of known content at known offsets. That is a small consideration against a very small
 * cost, and it goes this way round.
 *
 * **Unknown fields survive a round trip only for the version they belong to.** Gson drops what it
 * does not know, so a vault written by a newer build and edited by an older one would quietly lose
 * whatever the newer one added. [VaultDocument.version] is what stops that being silent: :secrets
 * refuses to *write* a document whose version is newer than [VaultDocument.DOCUMENT_VERSION], and
 * says why. Reading one is allowed — showing the passwords is the point of the app — but the file
 * stays as it is.
 */
object VaultJson {

    private val gson: Gson = GsonBuilder().create()

    fun encode(document: VaultDocument): ByteArray =
        gson.toJson(document).toByteArray(Charsets.UTF_8)

    /**
     * Parse a document, or null if the bytes are not one.
     *
     * A null here after a successful decrypt means something stranger than a wrong passphrase — the
     * key opened the body and what came out was not a vault document — so callers treat it as a
     * corrupt vault rather than a failed unlock.
     */
    fun decode(bytes: ByteArray): VaultDocument? = try {
        val parsed = gson.fromJson(String(bytes, Charsets.UTF_8), VaultDocument::class.java)
        // Gson populates fields reflectively and will leave a non-null-typed field null when the key
        // is absent, so these guards are real at runtime even though the compiler sees them as
        // constant. Normalising here rather than at every read site is what lets the rest of the
        // module treat a document's lists as lists.
        @Suppress("SENSELESS_COMPARISON")
        when {
            parsed == null -> null
            parsed.items == null -> parsed.copy(items = emptyList())
            else -> parsed.copy(items = parsed.items.map { it.normalised() })
        }
    } catch (_: Exception) {
        null
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun VaultItem.normalised(): VaultItem = copy(
        kind = kind ?: VaultItemKind.OTHER,
        title = title ?: "",
        username = username ?: "",
        secret = secret ?: "",
        fields = fields?.filter { it != null && it.name != null }?.map { it.copy(value = it.value ?: "") }
            ?: emptyList(),
        note = note ?: "",
        url = url ?: "",
        tags = tags?.filterNotNull() ?: emptyList(),
        totp = totp?.normalised(),
        history = history?.filter { it != null && it.secret != null && it.secret.isNotEmpty() } ?: emptyList()
    )

    /**
     * A second factor, made safe to show.
     *
     * Gson allocates without running the constructor, so a document that predates this field — or
     * one hand-edited during a recovery — arrives with nulls where enums and strings should be and
     * zeroes where the digit count and the period should be. Defaulting them here rather than at
     * every read site is what lets the rest of the module treat a stored [TotpConfig] as one that
     * works.
     *
     * A seed that will not decode returns null outright: an item that quietly shows no codes is
     * better than one that confidently shows wrong ones.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun TotpConfig.normalised(): TotpConfig? {
        if (secret == null || secret.isBlank()) return null
        return copy(
            algorithm = algorithm ?: TotpAlgorithm.SHA1,
            digits = digits.takeIf { it in TotpConfig.MIN_DIGITS..TotpConfig.MAX_DIGITS }
                ?: TotpConfig.DEFAULT_DIGITS,
            periodSeconds = periodSeconds.takeIf { it in TotpConfig.MIN_PERIOD..TotpConfig.MAX_PERIOD }
                ?: TotpConfig.DEFAULT_PERIOD_SECONDS,
            issuer = issuer ?: "",
            account = account ?: ""
        ).takeIf { it.isUsable }
    }
}
