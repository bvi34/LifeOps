package com.operations.sandbox.cloud

import com.operations.backupkit.cloud.AzureBlobListing
import com.operations.backupkit.cloud.AzureBlobStatus
import com.operations.backupkit.cloud.AzureBlobTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The three requests a scheduled backup makes: put an archive in the container, list what is
 * already there, and delete what has rolled off.
 *
 * Small on purpose, and with no Azure SDK behind it. A shared access signature is a query string,
 * so authenticating is attaching it to the URL — there is no request signing, no token exchange and
 * no credential chain to configure, which means this is `HttpURLConnection` and nothing else, the
 * same way the updater talks to GitHub. Pulling in the Azure Storage SDK would add several
 * megabytes and a Netty-shaped dependency tree to a sideloaded APK to save about forty lines.
 *
 * Everything that decides anything — the URL, what a status code means, whether a failure is worth
 * retrying, which blobs have rolled off — lives in `:backupkit`, where it is unit-tested off the
 * device. What is left here is a connection, a stream copy and a status code.
 */
class AzureBlobStore(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) {

    /**
     * One request's answer, already interpreted.
     *
     * [status] is 0 when there was no answer at all — offline, DNS, a connection cut mid-upload —
     * which [AzureBlobStatus] treats as transient, because it is.
     */
    data class Call(
        val status: Int,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val body: String? = null
    ) {
        val succeeded: Boolean get() = AzureBlobStatus.isSuccess(status)
        val transient: Boolean get() = AzureBlobStatus.isTransient(status)

        /**
         * The failure in the household's terms, with Azure's own error code when it sent one.
         *
         * The detail is only ever appended for a real HTTP answer, never for [status] 0. That is
         * not cosmetic: a status of 0 means an exception, and the JDK's network exceptions
         * routinely quote the URL they failed on — which here carries the shared access signature.
         * This string is stored in preferences and shown on screen, so it must not be able to
         * contain the credential.
         */
        fun describe(): String {
            val detail = errorMessage?.takeIf { it.isNotBlank() && status != 0 }
            return AzureBlobStatus.describe(status, errorCode) + (detail?.let { " — $it" }.orEmpty())
        }
    }

    /**
     * Upload [file] as a block blob called [blobName] (under the target's prefix).
     *
     * Streamed with a fixed content length rather than buffered: an archive of a household's whole
     * suite can be hundreds of megabytes, and `HttpURLConnection` will otherwise hold every byte in
     * memory until the request is sent, which on a phone is an OutOfMemoryError at 2am.
     *
     * A single `Put Blob` rather than staged blocks. The limit for one is 5000 MiB on any service
     * version this can be pointed at, which no household archive approaches; blocks would buy
     * resumability, which is not worth the complexity for a job that simply runs again tomorrow.
     */
    suspend fun upload(target: AzureBlobTarget, blobName: String, file: File): Call =
        withContext(Dispatchers.IO) {
            request(target.blobUrl(blobName), "PUT") { connection ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(file.length())
                connection.setRequestProperty("x-ms-blob-type", "BlockBlob")
                connection.setRequestProperty("x-ms-blob-content-type", ARCHIVE_CONTENT_TYPE)
                connection.setRequestProperty("Content-Type", ARCHIVE_CONTENT_TYPE)
                connection.outputStream.use { out ->
                    BufferedInputStream(file.inputStream()).use { input -> input.copyTo(out) }
                }
            }
        }

    /**
     * Every blob in the container under the target's prefix.
     *
     * Follows Azure's continuation markers to the end, so retention sees the whole container rather
     * than its first five thousand entries. Returns the names alongside the call, so a caller can
     * tell "nothing there" from "couldn't ask" — a difference that decides whether pruning is
     * skipped or reported.
     */
    suspend fun list(target: AzureBlobTarget): Listing = withContext(Dispatchers.IO) {
        val names = mutableListOf<String>()
        var marker: String? = null
        var pages = 0
        while (true) {
            val call = request(target.listUrl(marker), "GET") { }
            if (!call.succeeded) return@withContext Listing(call, names)
            val body = call.body.orEmpty()
            names += AzureBlobListing.blobNames(body)
            marker = AzureBlobListing.nextMarker(body)
            // A guard rather than a limit: a service that kept handing back the same marker would
            // otherwise spin here forever on a phone's battery.
            if (marker == null || ++pages >= MAX_LIST_PAGES) break
        }
        Listing(Call(HttpURLConnection.HTTP_OK), names)
    }

    /** Delete one blob by its full path within the container (prefix included). */
    suspend fun delete(target: AzureBlobTarget, blobPath: String): Call = withContext(Dispatchers.IO) {
        // The path already carries the prefix, so it is passed through the URL builder relative to
        // the container root rather than being prefixed a second time.
        val relative = blobPath.removePrefix(target.prefix)
        request(target.blobUrl(relative), "DELETE") { }
    }

    /** A listing, and how the asking went. [names] is empty and meaningless when [call] failed. */
    data class Listing(val call: Call, val names: List<String>)

    /**
     * One request, with the failure paths collapsed into a [Call] rather than thrown.
     *
     * Nothing here throws for a status code or for being offline: the caller's decisions are all
     * made from [Call], and a worker that has to catch exceptions to find out whether to retry is a
     * worker that will one day retry the wrong thing.
     */
    private inline fun request(url: String, method: String, body: (HttpURLConnection) -> Unit): Call {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("x-ms-version", SERVICE_VERSION)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            body(connection)
            val status = connection.responseCode
            if (AzureBlobStatus.isSuccess(status)) {
                Call(status, body = connection.inputStream?.bufferedReader()?.use { it.readText() })
            } else {
                // Azure explains itself twice: a header on every error, and an XML document on most.
                // The header is the one that is always there.
                val text = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Call(
                    status = status,
                    errorCode = connection.getHeaderField("x-ms-error-code")
                        ?: AzureBlobListing.errorCode(text),
                    errorMessage = AzureBlobListing.errorMessage(text),
                    body = text
                )
            }
        } catch (e: Exception) {
            Call(status = 0, errorMessage = e.message)
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        /** What the archive is, so a browser or the portal offers to download rather than render it. */
        const val ARCHIVE_CONTENT_TYPE = "application/zip"

        /**
         * The storage service version this speaks. A SAS carries its own `sv=`, so this only has to
         * be a version the account understands; pinned rather than left to the service default so a
         * change on Azure's side cannot alter what the requests mean.
         */
        const val SERVICE_VERSION = "2021-08-06"

        private const val USER_AGENT = "OperationsSandbox/backup"

        private const val CONNECT_TIMEOUT_MS = 30_000

        /** Generous: this is a read timeout on a socket carrying an archive over a phone's uplink. */
        private const val READ_TIMEOUT_MS = 120_000

        /** 5,000 blobs a page — ten pages is far more container than this app will ever write. */
        private const val MAX_LIST_PAGES = 10
    }
}
