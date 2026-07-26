package com.citation.core.capture

import com.citation.core.identity.IdentityKey

/**
 * Encoders/decoders for the **self-describing cluster id** a capture carries as its source id.
 *
 * A cluster id is `"<rung-tag>:<value>"`. Keeping it self-describing (rather than an opaque hash)
 * is what lets a note store *only this string* yet still recover, later and offline: which rung it
 * came from (for triage), and — for a book-identity capture — the typed [IdentityKey] itself (for
 * promotion). Book identities nest their kind so the exact key round-trips:
 * `book:isbn:9781449373320`, `book:rr:12345`, `book:sha:<hex>`.
 *
 * Values are trimmed but never lower-cased here (a URL is case-sensitive after the host); identity
 * normalisation stays the job of [IdentityKey] itself.
 */
object ClusterId {

    /** `url:<url>` — group by exact page URL. */
    fun ofUrl(url: String): String = "${ProvenanceRung.URL.tag}:${url.trim()}"

    /** `file:<filename>` — group by document filename. */
    fun ofFilename(filename: String): String = "${ProvenanceRung.FILENAME.tag}:${filename.trim()}"

    /**
     * `title:<normalized>` — group by work title when no machine identity exists (Kindle without an
     * ISBN). Normalised (lower-cased, punctuation folded to single spaces) so cosmetic variance —
     * "Designing Data-Intensive Applications" vs "Designing Data Intensive Applications" — still lands
     * in one cluster. The note's *display* title stays the original; only the grouping key is folded.
     */
    fun ofTitle(title: String): String = "${ProvenanceRung.TITLE.tag}:${normalizeTitle(title)}"

    private fun normalizeTitle(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** `app:<package>` — group by source app package name. */
    fun ofApp(appPackage: String): String = "${ProvenanceRung.APP_PACKAGE.tag}:${appPackage.trim()}"

    /** `ts:<epochMillis>` — the floor; every capture gets its own singleton cluster. */
    fun ofTimestamp(epochMillis: Long): String = "${ProvenanceRung.TIMESTAMP.tag}:$epochMillis"

    /** Encode a hard book identity into a `book:<kind>:<value>` cluster id. */
    fun ofBookIdentity(key: IdentityKey): String {
        val body = when (key) {
            is IdentityKey.Isbn -> "isbn:${key.normalized}"
            is IdentityKey.RoyalRoadId -> "rr:${key.fictionId}"
            is IdentityKey.PdfSha -> "sha:${key.normalized}"
        }
        return "${ProvenanceRung.BOOK_IDENTITY.tag}:$body"
    }

    /** Recover the typed book identity from a `book:…` cluster id, or `null` if it isn't one. */
    fun bookIdentityOf(clusterId: String): IdentityKey? {
        if (ProvenanceRung.ofClusterId(clusterId) != ProvenanceRung.BOOK_IDENTITY) return null
        val body = clusterId.substringAfter("${ProvenanceRung.BOOK_IDENTITY.tag}:", "")
        val kind = body.substringBefore(':', "")
        val value = body.substringAfter(':', "")
        if (value.isEmpty()) return null
        return when (kind) {
            "isbn" -> IdentityKey.Isbn(value)
            "rr" -> value.toLongOrNull()?.let { IdentityKey.RoyalRoadId(it) }
            "sha" -> IdentityKey.PdfSha(value)
            else -> null
        }
    }
}
