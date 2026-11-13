package com.operations.backupkit.transfer

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The old phone's side of a transfer: a small HTTP server that lives only as long as the transfer
 * screen is open.
 *
 * ## Why this, and not a link to Azure
 *
 * The suite holds a *container* SAS for its scheduled backups, and on purpose never an account key.
 * A SAS can't mint a narrower SAS, and Azure can't revoke a single ad-hoc SAS early without an
 * account key or an Entra sign-in. So the phone can't produce a "two blobs, read-only, ten minutes,
 * revoked when the screen closes" link. Serving the files itself gets the same result:
 *
 *  - **Scope.** It serves exactly two things under one unguessable path: the APK this phone is
 *    running, and the sealed bundle. Anything else gets a 404.
 *  - **Expiry and revocation.** When the timer ends or the screen closes, [stop] closes the socket,
 *    and every copy of the QR code points at nothing.
 *  - **Version match.** The APK is this phone's own installed file, not a release download.
 *
 * ## Who gets the bundle
 *
 * The APK is not secret, so anyone holding the path can download it. The bundle is held back until
 * the person at the old phone approves one specific device (see [approve]). A device asks by sending
 * a nonce; both screens derive a six-digit code from it ([TransferTicket.confirmationCode]), so the
 * person approves the request whose code matches the phone in their other hand. Only one device can
 * be approved. The bundle is sealed under a key the network never carries anyway.
 *
 * Plain HTTP over the LAN is enough because of that: the bundle is authenticated, encrypted data,
 * and the confirmation step stops a stranger on the same network from asking for it. The APK leg is
 * not authenticated. The new phone's installer only checks that the APK is signed, not by whom, on
 * a first install. That gap is noted on the transfer screen and in `docs/OPERATIONS_SANDBOX.md`.
 *
 * ## Routes, all under `/t/<token>/`
 *
 *  - `GET  /`        a landing page with the download link and the install steps.
 *  - `GET  app.apk`  the APK, with `Range` support.
 *  - `HEAD bundle`   state only, in the `X-Transfer-State` header.
 *  - `GET  bundle`   the sealed bundle once approved and ready, with `Range` support so a dropped
 *    link resumes rather than restarts.
 *
 * Every `bundle` request must carry `X-Transfer-Nonce`.
 */
class TransferServer(
    private val ticketToken: String,
    private val expiresAt: Long,
    private val apk: ServedFile?,
    private val landing: LandingPage,
    private val listener: Listener = object : Listener {},
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** A file on disk and how to describe it to a browser. */
    data class ServedFile(val file: File, val downloadName: String, val contentType: String)

    /** The text on the landing page. Everything is HTML-escaped before it is written. */
    data class LandingPage(val appName: String, val versionName: String, val apkSha256: String?)

    enum class DeviceState { WAITING, APPROVED, DENIED }

    /** Why the server stopped. Shown on the old phone's screen. */
    enum class StopReason { CLOSED, EXPIRED, TOO_MANY_WRONG_ADDRESSES, FAILED }

    /** Callbacks, made on the server's own threads. The caller hops to its UI thread. */
    interface Listener {
        /** A device asked for the bundle and is waiting for **Allow**. */
        fun onDeviceAsked(nonce: String) {}
        fun onApkRequested() {}
        /** [sent] is the furthest byte delivered so far, so a resumed download doesn't count twice. */
        fun onBundleProgress(sent: Long, total: Long) {}
        fun onBundleDelivered() {}
        fun onStopped(reason: StopReason) {}
    }

    private val running = AtomicBoolean(false)
    private val wrongAddresses = AtomicInteger(0)
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    private val lock = Any()
    private val devices = LinkedHashMap<String, DeviceState>()
    @Volatile private var bundle: File? = null
    @Volatile private var furthestSent: Long = 0
    @Volatile private var delivered = false

    val isRunning: Boolean get() = running.get()

    /** Bind to [address] (the phone's Wi-Fi address, never a wildcard) and return the port chosen. */
    fun start(address: InetAddress, port: Int = 0): Int {
        check(!running.get()) { "Already serving" }
        val server = ServerSocket(port, BACKLOG, address).apply { soTimeout = ACCEPT_POLL_MS }
        socket = server
        workers = Executors.newFixedThreadPool(MAX_CONNECTIONS) { r -> Thread(r, "transfer-conn").apply { isDaemon = true } }
        running.set(true)
        acceptThread = Thread({ acceptLoop(server) }, "transfer-accept").apply {
            isDaemon = true
            start()
        }
        return server.localPort
    }

    /** The bundle is sealed and may be served once a device is approved. */
    fun bundleReady(file: File) {
        bundle = file
    }

    fun approve(nonce: String) = decide(nonce, DeviceState.APPROVED)

    fun deny(nonce: String) = decide(nonce, DeviceState.DENIED)

    fun stateOf(nonce: String): DeviceState? = synchronized(lock) { devices[nonce] }

    /** Close the socket. Safe to call more than once, and from any thread. */
    fun stop(reason: StopReason = StopReason.CLOSED) {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        workers?.shutdownNow()
        listener.onStopped(reason)
    }

    private fun decide(nonce: String, state: DeviceState) {
        synchronized(lock) {
            if (nonce !in devices) return
            // One phone, once. Approving a second device would hand the same data to two places
            // on a screen that only ever shows one code.
            if (state == DeviceState.APPROVED && devices.values.any { it == DeviceState.APPROVED }) return
            devices[nonce] = state
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            if (clock() >= expiresAt) {
                stop(StopReason.EXPIRED)
                break
            }
            val client = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (running.get()) stop(StopReason.FAILED)
                break
            }
            try {
                workers?.execute { handle(client) } ?: client.close()
            } catch (e: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { conn ->
            try {
                conn.soTimeout = READ_TIMEOUT_MS
                val request = HttpRequest.read(conn.getInputStream()) ?: return
                val out = BufferedOutputStream(conn.getOutputStream(), 64 * 1024)
                respond(request, out)
                out.flush()
            } catch (e: IOException) {
                // The other side went away. A resumed download starts a new connection.
            }
        }
    }

    private fun respond(request: HttpRequest, out: OutputStream) {
        if (!running.get()) return
        if (clock() >= expiresAt) {
            simple(out, 410, "Gone", "This transfer has ended. Start a new one on the old phone.", request.head)
            stop(StopReason.EXPIRED)
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            simple(out, 405, "Method Not Allowed", "", request.head)
            return
        }
        val path = request.path
        if (!path.startsWith("/t/")) {
            // The browser's own /favicon.ico and the like. Not an attempt, so not counted.
            simple(out, 404, "Not Found", "", request.head)
            return
        }
        val rest = path.removePrefix("/t/")
        val token = rest.substringBefore('/')
        val route = rest.substringAfter('/', missingDelimiterValue = "\u0000")
        if (!sameToken(token)) {
            if (wrongAddresses.incrementAndGet() >= MAX_WRONG_ADDRESSES) {
                simple(out, 404, "Not Found", "", request.head)
                stop(StopReason.TOO_MANY_WRONG_ADDRESSES)
                return
            }
            simple(out, 404, "Not Found", "", request.head)
            return
        }
        when (route) {
            "" -> page(out, request.head)
            "app.apk" -> apk(out, request)
            "bundle" -> bundle(out, request)
            else -> simple(out, 404, "Not Found", "", request.head)
        }
    }

    private fun sameToken(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), ticketToken.toByteArray(Charsets.UTF_8))

    private fun apk(out: OutputStream, request: HttpRequest) {
        val served = apk
        if (served == null || !served.file.isFile) {
            simple(out, 404, "Not Found", "The app file isn't available on this phone.", request.head)
            return
        }
        listener.onApkRequested()
        sendFile(
            out, request, served.file, served.contentType,
            extraHeaders = listOf("Content-Disposition" to "attachment; filename=\"${served.downloadName}\""),
            progress = null
        )
    }

    private fun bundle(out: OutputStream, request: HttpRequest) {
        val nonce = request.header(NONCE_HEADER)
        if (!TransferTicket.isNonce(nonce)) {
            simple(out, 400, "Bad Request", "", request.head)
            return
        }
        nonce!!
        val (state, isNew) = synchronized(lock) {
            val existing = devices[nonce]
            when {
                existing != null -> existing to false
                devices.size >= MAX_DEVICES -> DeviceState.DENIED to false
                else -> DeviceState.WAITING.also { devices[nonce] = it } to true
            }
        }
        if (isNew) listener.onDeviceAsked(nonce)
        val file = bundle
        when {
            state == DeviceState.WAITING -> stateOnly(out, 202, "Accepted", STATE_WAITING, request.head)
            state == DeviceState.DENIED -> stateOnly(out, 403, "Forbidden", STATE_DENIED, request.head)
            file == null -> stateOnly(out, 503, "Service Unavailable", STATE_PREPARING, request.head)
            request.head -> {
                headers(out, 200, "OK", listOf(
                    STATE_HEADER to STATE_READY,
                    "Content-Length" to file.length().toString(),
                    "Accept-Ranges" to "bytes"
                ))
            }
            else -> sendFile(out, request, file, "application/octet-stream",
                extraHeaders = listOf(STATE_HEADER to STATE_READY),
                progress = { end -> recordProgress(end, file.length()) })
        }
    }

    private fun recordProgress(end: Long, total: Long) {
        synchronized(lock) {
            if (end > furthestSent) furthestSent = end
        }
        listener.onBundleProgress(furthestSent, total)
        if (furthestSent >= total && !delivered) {
            delivered = true
            listener.onBundleDelivered()
        }
    }

    private fun sendFile(
        out: OutputStream,
        request: HttpRequest,
        file: File,
        contentType: String,
        extraHeaders: List<Pair<String, String>>,
        progress: ((Long) -> Unit)?
    ) {
        val length = file.length()
        val range = request.header("Range")?.let { HttpRange.parse(it, length) }
        if (range is HttpRange.Unsatisfiable) {
            headers(out, 416, "Range Not Satisfiable", listOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
            return
        }
        val (start, endExclusive) = (range as? HttpRange.Slice)?.let { it.start to it.endExclusive } ?: (0L to length)
        val partial = range is HttpRange.Slice
        val common = listOf(
            "Content-Type" to contentType,
            "Content-Length" to (endExclusive - start).toString(),
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store"
        ) + extraHeaders + (if (partial) listOf("Content-Range" to "bytes $start-${endExclusive - 1}/$length") else emptyList())
        if (partial) headers(out, 206, "Partial Content", common) else headers(out, 200, "OK", common)
        if (request.head) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var position = start
            var sinceReport = 0L
            while (position < endExclusive && running.get()) {
                val want = minOf(buffer.size.toLong(), endExclusive - position).toInt()
                val read = raf.read(buffer, 0, want)
                if (read < 0) break
                out.write(buffer, 0, read)
                position += read
                sinceReport += read
                if (progress != null && (sinceReport >= PROGRESS_STEP || position == endExclusive)) {
                    out.flush()
                    progress(position)
                    sinceReport = 0
                }
            }
        }
    }

    private fun page(out: OutputStream, head: Boolean) {
        val body = landingHtml().toByteArray(Charsets.UTF_8)
        headers(out, 200, "OK", listOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to body.size.toString(),
            "Cache-Control" to "no-store",
            "Referrer-Policy" to "no-referrer",
            "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'"
        ))
        if (!head) out.write(body)
    }

    private fun landingHtml(): String {
        val name = escape(landing.appName)
        val version = escape(landing.versionName)
        val apkLink = if (apk != null) "<a class=\"btn\" href=\"app.apk\" download>Download $name $version</a>" else
            "<p>The app file isn't available on the old phone. Install $name another way, then come back to this code.</p>"
        val checksum = landing.apkSha256?.let { "<p class=\"small\">SHA-256: <code>${escape(it)}</code></p>" }.orEmpty()
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Set up $name</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:24px 16px;background:#f6f5f2;color:#1c1b19;line-height:1.5}
@media (prefers-color-scheme:dark){body{background:#151513;color:#ecebe6}.small{color:#a9a79f}code{background:#2a2925}}
main{max-width:520px;margin:0 auto}
h1{font-size:1.4rem;margin:0 0 12px}
.btn{display:block;text-align:center;padding:14px;border-radius:12px;background:#2f5d50;color:#fff;text-decoration:none;font-weight:600;margin:20px 0}
ol{padding-left:20px}li{margin:6px 0}.small{font-size:.85rem;color:#5d5b55;word-break:break-all}
code{background:#e8e6e0;padding:1px 4px;border-radius:4px}
</style></head><body><main>
<h1>Set up $name on this phone</h1>
$apkLink
<ol>
<li>When the download finishes, open it.</li>
<li>If Android says the browser isn't allowed to install apps, tap <b>Settings</b>, turn on <b>Allow from this source</b>, and go back.</li>
<li>Tap <b>Install</b>.</li>
<li>Open $name and choose <b>Restore from old phone</b>.</li>
<li>Scan the same code on the old phone again, check that both phones show the same six digits, and tap <b>Allow</b> on the old phone.</li>
</ol>
<p class="small">This page is served by your old phone over your Wi-Fi, and stops when you close the transfer screen there. Your data is not on this page.</p>
$checksum
</main></body></html>"""
    }

    private fun stateOnly(out: OutputStream, code: Int, reason: String, state: String, head: Boolean) {
        val body = state.toByteArray(Charsets.UTF_8)
        headers(out, code, reason, listOf(
            STATE_HEADER to state,
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to body.size.toString()
        ))
        if (!head) out.write(body)
    }

    private fun simple(out: OutputStream, code: Int, reason: String, text: String, head: Boolean) {
        val body = text.toByteArray(Charsets.UTF_8)
        headers(out, code, reason, listOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to body.size.toString()
        ))
        if (!head) out.write(body)
    }

    private fun headers(out: OutputStream, code: Int, reason: String, headers: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    companion object {
        const val NONCE_HEADER = "X-Transfer-Nonce"
        const val STATE_HEADER = "X-Transfer-State"
        const val STATE_WAITING = "waiting"
        const val STATE_DENIED = "denied"
        const val STATE_PREPARING = "preparing"
        const val STATE_READY = "ready"

        private const val BACKLOG = 8
        private const val MAX_CONNECTIONS = 4
        private const val ACCEPT_POLL_MS = 500
        private const val READ_TIMEOUT_MS = 30_000
        private const val PROGRESS_STEP = 256L * 1024

        /** More distinct devices than this asking at once is not one household moving phones. */
        private const val MAX_DEVICES = 4

        /**
         * Requests under `/t/` with the wrong token before the server gives up. The token is 128
         * bits, so this isn't about guessing it. A device probing paths on the LAN is a reason to
         * stop serving, and the household can start a fresh transfer.
         */
        private const val MAX_WRONG_ADDRESSES = 20
    }
}

/** One parsed request line and its headers. Bodies are never read: no route accepts one. */
internal class HttpRequest(val method: String, val path: String, private val headers: Map<String, String>) {

    val head: Boolean get() = method == "HEAD"

    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        private const val MAX_HEAD_BYTES = 8 * 1024

        /** Null for an empty, oversized or malformed request. The connection is then just closed. */
        fun read(input: InputStream): HttpRequest? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            var previous = -1
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (++total > MAX_HEAD_BYTES) return null
                if (b == '\n'.code) {
                    if (previous == '\r'.code && line.isNotEmpty()) line.setLength(line.length - 1)
                    if (line.isEmpty()) break
                    lines += line.toString()
                    line.setLength(0)
                } else {
                    line.append(b.toChar())
                }
                previous = b
            }
            val requestLine = lines.firstOrNull()?.split(' ') ?: return null
            if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return null
            val target = requestLine[1]
            if (!target.startsWith('/')) return null
            val path = target.substringBefore('?').substringBefore('#')
            val headers = lines.drop(1).mapNotNull { h ->
                val colon = h.indexOf(':')
                if (colon <= 0) null else h.substring(0, colon).trim().lowercase() to h.substring(colon + 1).trim()
            }.toMap()
            return HttpRequest(requestLine[0], path, headers)
        }
    }
}

/** The single-range subset of RFC 9110 `Range` that a resuming download needs. */
sealed interface HttpRange {
    data class Slice(val start: Long, val endExclusive: Long) : HttpRange
    object Unsatisfiable : HttpRange

    companion object {
        /** Null means "ignore the header and send the whole file", which RFC 9110 allows. */
        fun parse(header: String, length: Long): HttpRange? {
            val spec = header.trim()
            if (!spec.startsWith("bytes=")) return null
            val ranges = spec.removePrefix("bytes=").split(',')
            if (ranges.size != 1) return null
            val (first, last) = ranges[0].trim().split('-', limit = 2).takeIf { it.size == 2 } ?: return null
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: return null
                if (suffix <= 0) return Unsatisfiable
                return Slice(maxOf(0, length - suffix), length)
            }
            val start = first.toLongOrNull() ?: return null
            if (start < 0) return null
            if (start >= length) return Unsatisfiable
            val end = if (last.isEmpty()) length - 1 else (last.toLongOrNull() ?: return null)
            if (end < start) return null
            return Slice(start, minOf(end, length - 1) + 1)
        }
    }
}
