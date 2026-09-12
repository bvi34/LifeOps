package com.operations.backupkit.cloud

import java.net.URLEncoder

/**
 * Where a scheduled archive is sent: one container in one Azure Storage account, plus the shared
 * access signature that is allowed to write into it.
 *
 * Pure data and string arithmetic, in `:backupkit` with the rest of the format, so the part of
 * "back up to Azure" that is easy to get quietly wrong — the URL, the prefix, whether the
 * credential is even the right shape — is unit-tested on the JVM rather than discovered by a
 * household whose backups silently stopped landing.
 *
 * ## Why a SAS and not an account key
 *
 * A storage account key is the account: read, write, delete, every container, forever. It is also
 * what every "upload to Azure from Android" example reaches for, because signing a request with it
 * is one HMAC. Putting one on a phone means that a lost phone is a lost account.
 *
 * A shared access signature is the same credential narrowed on all four axes the household cares
 * about — one container, create/write only, an expiry date, and revocable from the portal without
 * touching anything else — and it is a query string, which means the request that carries it needs
 * no signing code at all. The upload becomes a plain `PUT` of a file, which is the whole of
 * [com.operations.sandbox.cloud.AzureBlobStore] on the Android side.
 *
 * The cost is that a SAS expires, which is not a cost but the point: [AzureSas] reads the expiry
 * out of the token so the settings screen can say "this stops working in eleven days" before the
 * day it stops working.
 */
data class AzureBlobTarget(

    /** The storage account name — the `myhousehold` in `myhousehold.blob.core.windows.net`. */
    val account: String,

    /** The container the archives are written into. It must already exist; nothing here creates it. */
    val container: String,

    /**
     * An optional folder inside the container, normalized to end in `/` (or empty). Blob storage
     * has no directories — a prefix is simply part of the name — but the portal renders one as a
     * folder, which is enough for a household that keeps more than this in the same container.
     */
    val prefix: String = "",

    /** The shared access signature's query string, without its leading `?`. */
    val sasToken: String = "",

    /**
     * The storage endpoint suffix. `core.windows.net` for the global cloud; the sovereign clouds
     * (`core.usgovcloudapi.net`, `core.chinacloudapi.cn`) differ only here, so supporting them is a
     * field rather than a fork.
     */
    val endpointSuffix: String = DEFAULT_ENDPOINT_SUFFIX
) {

    /** `https://account.blob.core.windows.net` — everything else is built on this. */
    val blobEndpoint: String get() = "https://$account.blob.$endpointSuffix"

    /** The full name a blob is stored under: the prefix (if any) and the archive's file name. */
    fun blobPath(blobName: String): String = prefix + blobName.trimStart('/')

    /** The URL a `PUT` (or `DELETE`) of one archive goes to, SAS attached. */
    fun blobUrl(blobName: String): String =
        "$blobEndpoint/$container/${encodePath(blobPath(blobName))}${query(emptyList())}"

    /**
     * The URL that lists what is already in the container under this prefix — what retention needs
     * before it can decide anything. [marker] continues a listing Azure truncated; see
     * [AzureBlobListing.nextMarker].
     */
    fun listUrl(marker: String? = null): String {
        val params = buildList {
            add("restype=container")
            add("comp=list")
            if (prefix.isNotEmpty()) add("prefix=${encodeValue(prefix)}")
            if (!marker.isNullOrBlank()) add("marker=${encodeValue(marker)}")
        }
        return "$blobEndpoint/$container${query(params)}"
    }

    /** How the destination reads on the settings screen: `myhousehold / backups / nightly/`. */
    fun describe(): String = buildString {
        append(account)
        append(" / ")
        append(container)
        if (prefix.isNotEmpty()) {
            append(" / ")
            append(prefix)
        }
    }

    /** The query string for a request: the caller's parameters, then the SAS, both optional. */
    private fun query(params: List<String>): String {
        val all = params + listOfNotNull(sasToken.takeIf { it.isNotEmpty() })
        return if (all.isEmpty()) "" else "?" + all.joinToString("&")
    }

    companion object {

        /** The global cloud. The sovereign ones differ only in this string. */
        const val DEFAULT_ENDPOINT_SUFFIX = "core.windows.net"

        private val ACCOUNT = Regex("[a-z0-9]{3,24}")
        private val CONTAINER = Regex("[a-z0-9]([a-z0-9-]{1,61})[a-z0-9]")
        private val PREFIX_SEGMENT = Regex("[A-Za-z0-9._-]+")

        /**
         * Check a destination typed into the settings screen, and hand back either the target it
         * describes or the first thing wrong with it.
         *
         * One closed answer rather than a validator plus a constructor, for the reason
         * [com.operations.sandbox.ui.UpdateState] is one: a screen that holds "is it valid" and
         * "what is the target" as two separate values is a screen that can show a green tick over
         * an address nothing will ever reach.
         */
        fun check(
            account: String,
            container: String,
            prefix: String = "",
            sasToken: String = "",
            endpointSuffix: String = DEFAULT_ENDPOINT_SUFFIX
        ): TargetCheck {
            val cleanAccount = account.trim().lowercase()
            val cleanContainer = container.trim().lowercase()
            val cleanPrefix = normalizePrefix(prefix)
            val cleanSas = normalizeSas(sasToken)
            val cleanSuffix = endpointSuffix.trim().lowercase().trim('.')

            accountProblem(cleanAccount)?.let { return TargetCheck.Incomplete(it) }
            containerProblem(cleanContainer)?.let { return TargetCheck.Incomplete(it) }
            prefixProblem(cleanPrefix)?.let { return TargetCheck.Incomplete(it) }
            sasProblem(cleanSas)?.let { return TargetCheck.Incomplete(it) }
            if (cleanSuffix.isEmpty()) return TargetCheck.Incomplete("The storage endpoint is empty.")

            return TargetCheck.Ready(
                AzureBlobTarget(
                    account = cleanAccount,
                    container = cleanContainer,
                    prefix = cleanPrefix,
                    sasToken = cleanSas,
                    endpointSuffix = cleanSuffix
                )
            )
        }

        /** Azure's own account-name rule: 3–24 characters, lower-case letters and digits only. */
        fun accountProblem(account: String): String? = when {
            account.isEmpty() -> "Enter the storage account name."
            !ACCOUNT.matches(account) ->
                "A storage account name is 3–24 lower-case letters and digits — \"$account\" isn't one."
            else -> null
        }

        /**
         * Azure's container rule, which is stricter than it looks: 3–63 characters of lower-case
         * letters, digits and hyphens, starting and ending on a letter or digit, and never two
         * hyphens in a row.
         */
        fun containerProblem(container: String): String? = when {
            container.isEmpty() -> "Enter the container to write archives into."
            !CONTAINER.matches(container) ->
                "A container name is 3–63 lower-case letters, digits and hyphens, starting and " +
                    "ending with a letter or digit — \"$container\" isn't one."
            container.contains("--") -> "A container name can't hold two hyphens in a row."
            else -> null
        }

        /**
         * The prefix is ours to be strict about — nothing forces the shape on us, and a name with a
         * `..` or a stray `//` in it is a name that reads back as something else later.
         */
        fun prefixProblem(prefix: String): String? {
            if (prefix.isEmpty()) return null
            val segments = prefix.trimEnd('/').split('/')
            if (segments.any { it.isEmpty() }) return "The folder can't contain an empty step (`//`)."
            if (segments.any { it == "." || it == ".." }) return "The folder can't contain `.` or `..`."
            if (segments.any { !PREFIX_SEGMENT.matches(it) }) {
                return "The folder may only hold letters, digits, `.`, `_`, `-` and `/`."
            }
            return null
        }

        /**
         * The shallowest possible check on the credential: that it is a query string carrying a
         * signature. Whether it is *the right* signature is Azure's to answer, and it answers with
         * a 403 the first time it is used — which the settings screen's "Back up now" button exists
         * to provoke while somebody is watching.
         */
        fun sasProblem(sasToken: String): String? = when {
            sasToken.isEmpty() -> "Paste the container's shared access signature (SAS)."
            AzureSas.param(sasToken, "sig") == null ->
                "That doesn't look like a SAS — the token Azure gives you contains `sig=`."
            else -> null
        }

        /** Trim, drop any leading slash, and end on one so a prefix concatenates into a name. */
        fun normalizePrefix(raw: String): String {
            val trimmed = raw.trim().trim('/')
            return if (trimmed.isEmpty()) "" else "$trimmed/"
        }

        /**
         * Accept the token however it was copied. The portal hands out `?sv=…`, the CLI hands out
         * `sv=…`, and somebody who copies the whole blob URL gets everything after the `?` — all
         * three are the same credential and none of them should be a support question.
         */
        fun normalizeSas(raw: String): String {
            val trimmed = raw.trim()
            val afterQuestionMark = trimmed.substringAfterLast('?', trimmed)
            return afterQuestionMark.trim().trimStart('?')
        }

        private fun encodePath(path: String): String =
            path.split('/').joinToString("/") { encodeValue(it) }

        private fun encodeValue(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/** Either a destination that can be written to, or the first reason it can't be. */
sealed interface TargetCheck {

    data class Ready(val target: AzureBlobTarget) : TargetCheck

    /** Not an error, in the ordinary case: it is what a half-filled settings form looks like. */
    data class Incomplete(val problem: String) : TargetCheck
}
