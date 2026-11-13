package com.operations.backupkit.transfer

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The new phone's side: ask the old phone for the bundle, wait for **Allow**, and download it,
 * resuming from the last byte received if the Wi-Fi drops.
 *
 * Raw sockets rather than `HttpURLConnection`. Android refuses cleartext HTTP through the platform
 * client unless the app opts in app-wide, and the only cleartext this suite should ever speak is
 * this one conversation with one LAN address. A socket isn't covered by that policy, so the opt-in
 * stays off. What crosses this socket is a GCM-sealed bundle whose key never touches the network
 * (see [TransferBundle], [TransferTicket]).
 */
class TransferClient(
    private val ticket: TransferTicket,
    private val nonce: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    private val sleep: (Long) -> Unit = Thread::sleep
) {

    sealed interface State {
        object Waiting : State
        object Denied : State
        object Preparing : State
        data class Ready(val length: Long) : State
        /** The old phone closed the transfer, or the code is for a transfer that ended. */
        object Gone : State
        data class Unreachable(val reason: String) : State
    }

    init {
        require(TransferTicket.isNonce(nonce))
    }

    /** One `HEAD bundle`: where the request stands. Registers this device on the first call. */
    fun poll(): State = try {
        request("HEAD", rangeStart = null).use { response ->
            when (response.state) {
                TransferServer.STATE_WAITING -> State.Waiting
                TransferServer.STATE_DENIED -> State.Denied
                TransferServer.STATE_PREPARING -> State.Preparing
                TransferServer.STATE_READY -> State.Ready(response.contentLength ?: -1)
                else -> if (response.status == 404 || response.status == 410) State.Gone
                else State.Unreachable("The old phone answered with ${response.status}.")
            }
        }
    } catch (e: IOException) {
        State.Unreachable(describe(e))
    }

    /**
     * Download the bundle into [dest], appending to whatever is already there.
     *
     * [dest] is kept between attempts, so an interrupted download picks up at its current length.
     * Gives up after [maxAttempts] consecutive failures without progress.
     */
    fun download(
        dest: File,
        total: Long,
        maxAttempts: Int = 6,
        onProgress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ) {
        var failures = 0
        while (true) {
            if (cancelled()) throw IOException("Cancelled")
            val have = if (dest.exists()) dest.length() else 0L
            if (total >= 0 && have == total) return
            if (total >= 0 && have > total) {
                dest.delete()
                continue
            }
            val before = have
            try {
                request("GET", rangeStart = have.takeIf { it > 0 }).use { response ->
                    when (response.status) {
                        200 -> appendBody(response, dest, append = false, onProgress, cancelled)
                        206 -> appendBody(response, dest, append = true, onProgress, cancelled)
                        416 -> { dest.delete() }
                        403 -> throw TransferBundle.Refused("The old phone declined this phone.")
                        404, 410 -> throw TransferBundle.Refused("The old phone closed the transfer. Start a new one and scan again.")
                        else -> throw IOException("The old phone answered with ${response.status}.")
                    }
                }
                if (total < 0) return
            } catch (e: IOException) {
                if (cancelled()) throw e
                val progressed = dest.exists() && dest.length() > before
                failures = if (progressed) 1 else failures + 1
                if (failures >= maxAttempts) throw IOException(describe(e), e)
                sleep(minOf(8_000L, 500L shl (failures - 1)))
            }
        }
    }

    private fun appendBody(
        response: Response,
        dest: File,
        append: Boolean,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val expected = response.contentLength
        var received = 0L
        FileOutputStream(dest, append).use { out ->
            val buffer = ByteArray(64 * 1024)
            var written = if (append) dest.length() else 0L
            while (expected == null || received < expected) {
                if (cancelled()) throw IOException("Cancelled")
                val want = if (expected == null) buffer.size else minOf(buffer.size.toLong(), expected - received).toInt()
                val read = response.body.read(buffer, 0, want)
                if (read < 0) break
                out.write(buffer, 0, read)
                received += read
                written += read
                onProgress(written)
            }
        }
        if (expected != null && received < expected) throw IOException("The connection dropped.")
    }

    private class Response(
        val socket: Socket,
        val status: Int,
        val headers: Map<String, String>,
        val body: InputStream
    ) : AutoCloseable {
        val state: String? get() = headers[TransferServer.STATE_HEADER.lowercase()]
        val contentLength: Long? get() = headers["content-length"]?.toLongOrNull()
        override fun close() { runCatching { socket.close() } }
    }

    private fun request(method: String, rangeStart: Long?): Response {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ticket.host, ticket.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val hostHeader = (if (ticket.host.contains(':')) "[${ticket.host}]" else ticket.host) + ":${ticket.port}"
            val head = buildString {
                append(method).append(' ').append(ticket.pathPrefix).append("bundle HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                append(TransferServer.NONCE_HEADER).append(": ").append(nonce).append("\r\n")
                if (rangeStart != null) append("Range: bytes=").append(rangeStart).append("-\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val statusLine = readLine(input) ?: throw IOException("The old phone closed the connection.")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Unreadable answer.")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("The old phone closed the connection.")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            return Response(socket, status, headers, input)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length > 8 * 1024) throw IOException("Header too long.")
            line.append(b.toChar())
        }
    }

    /** Socket messages can include the address; that's fine here, as it carries no secret. */
    private fun describe(e: IOException): String = when (e) {
        is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Can't reach the old phone. Check that both phones are on the same Wi-Fi and the transfer screen is still open."
        is java.net.SocketTimeoutException -> "The old phone stopped answering."
        else -> e.message ?: "The connection failed."
    }
}
