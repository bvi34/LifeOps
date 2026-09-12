package com.operations.backupkit.cloud

/**
 * Reading Azure's container listing, and reading its failures.
 *
 * `List Blobs` answers in XML, and the two things needed out of it are each one element deep: the
 * name of every blob, and the marker that says the answer was truncated. That is not worth an XML
 * parser on the request path — and more to the point, `javax.xml` behaves differently on the JVM
 * and on Android, which would make this the one piece of the cloud backup that could not be tested
 * here. So it is matched, narrowly, against recorded responses.
 *
 * The narrowness is deliberate: names are taken only from inside `<Blob>` elements, so the
 * `<Name>` that a `<BlobPrefix>` carries (when a listing is asked for with a delimiter) can never
 * be mistaken for a blob and handed to a deletion loop.
 */
object AzureBlobListing {

    private val BLOB = Regex("""<Blob>(.*?)</Blob>""", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("""<Name>(.*?)</Name>""", RegexOption.DOT_MATCHES_ALL)
    private val NEXT_MARKER = Regex("""<NextMarker>(.*?)</NextMarker>""", RegexOption.DOT_MATCHES_ALL)
    private val ERROR_CODE = Regex("""<Code>(.*?)</Code>""", RegexOption.DOT_MATCHES_ALL)
    private val ERROR_MESSAGE = Regex("""<Message>(.*?)</Message>""", RegexOption.DOT_MATCHES_ALL)

    /** Every blob name in one page of a listing, in the order Azure returned them. */
    fun blobNames(xml: String): List<String> =
        BLOB.findAll(xml).mapNotNull { blob ->
            NAME.find(blob.groupValues[1])?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
        }.toList()

    /**
     * The continuation marker, or null when the listing was complete. Azure always writes the
     * element and leaves it empty on the last page, so "present" is not the same as "more".
     */
    fun nextMarker(xml: String): String? =
        NEXT_MARKER.find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Azure's own error code out of a failed response body (`AuthenticationFailed`,
     * `ContainerNotFound`, `AuthorizationPermissionMismatch`), or null if the body isn't one of its
     * error documents. Worth surfacing verbatim: it is the string the household will search for.
     */
    fun errorCode(body: String?): String? =
        body?.let { ERROR_CODE.find(it)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty) }

    /** The human sentence Azure attached to the failure, first line only. */
    fun errorMessage(body: String?): String? =
        body?.let {
            ERROR_MESSAGE.find(it)?.groupValues?.get(1)
                ?.let(::unescape)?.lineSequence()?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
        }

    /** The five entities XML requires. Blob names are ours, but the listing is not ours to trust. */
    private fun unescape(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/**
 * What an HTTP status from blob storage means for a backup, separated from the code that made the
 * request so both halves can be tested.
 *
 * The distinction that matters is [isTransient]: a worker that returns "retry" for a wrong
 * credential will retry it, with backoff, until the phone is thrown away — and one that gives up on
 * a timeout loses the night's archive over a passing tunnel. Everything else here is wording.
 */
object AzureBlobStatus {

    fun isSuccess(status: Int): Boolean = status in 200..299

    /**
     * Worth trying again later: a timeout, a throttle, or anything the server is blaming on itself.
     * Azure throttles a burst with 503 `ServerBusy` and expects exactly this.
     */
    fun isTransient(status: Int): Boolean =
        status == 0 || status == 408 || status == 429 || status in 500..599

    /**
     * What went wrong, in the household's terms rather than the protocol's — [code] is Azure's own
     * error code when the body carried one (see [AzureBlobListing.errorCode]).
     *
     * 403 is the one that earns its sentence: it is what an expired signature, a signature for the
     * wrong container, and a signature without write permission all look like, and "Forbidden" sends
     * somebody to check the wrong thing.
     */
    fun describe(status: Int, code: String? = null): String {
        val suffix = code?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when {
            isSuccess(status) -> "Uploaded$suffix"
            status == 0 -> "Couldn't reach the storage account$suffix"
            status == 400 -> "Azure rejected the request$suffix"
            status == 401 -> "The signature wasn't accepted$suffix"
            status == 403 ->
                "Azure refused the upload$suffix — the signature may have expired, be for another " +
                    "container, or not allow writing"
            status == 404 -> "No such container in that storage account$suffix"
            status == 409 -> "The container is in a state that refuses writes$suffix"
            status == 413 -> "The archive is larger than this upload allows$suffix"
            status == 429 -> "Azure is throttling this account$suffix — it will be tried again"
            status in 500..599 -> "Azure had a problem (HTTP $status)$suffix — it will be tried again"
            else -> "Upload failed (HTTP $status)$suffix"
        }
    }
}
