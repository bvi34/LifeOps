package com.operations.backupkit.transfer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Both halves over loopback: the old phone's server and the new phone's client, end to end. */
class TransferServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val loopback = InetAddress.getLoopbackAddress()
    private val now = AtomicLong(1_000_000)
    private val events = CopyOnWriteArrayList<String>()
    private var server: TransferServer? = null

    private val listener = object : TransferServer.Listener {
        override fun onDeviceAsked(nonce: String) { events += "asked:$nonce" }
        override fun onApkRequested() { events += "apk" }
        override fun onBundleDelivered() { events += "delivered" }
        override fun onStopped(reason: TransferServer.StopReason) { events += "stopped:$reason" }
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun startServer(apk: File? = null, ttl: Long = TransferTicket.DEFAULT_TTL_MS): TransferTicket {
        val draft = TransferTicket.mint(loopback.hostAddress, 1, now.get(), ttl)
        val s = TransferServer(
            ticketToken = draft.token,
            expiresAt = draft.expiresAt,
            apk = apk?.let { TransferServer.ServedFile(it, "sandbox.apk", "application/vnd.android.package-archive") },
            landing = TransferServer.LandingPage("Operations <Sandbox>", "1.2.3", "abc123"),
            listener = listener,
            clock = { now.get() }
        )
        server = s
        val port = s.start(loopback)
        return draft.copy(port = port)
    }

    private fun sealedBundle(ticket: TransferTicket, size: Int = 300_000): Pair<ByteArray, File> {
        val plain = tmp.newFile().apply { writeBytes(Random(7).nextBytes(size)) }
        val sealed = tmp.newFile()
        sealed.outputStream().use { TransferBundle.seal(plain, ticket.key, 9, it, chunkSize = 64 * 1024) }
        return plain.readBytes() to sealed
    }

    private fun raw(ticket: TransferTicket, requestLine: String, vararg headers: String): String =
        Socket(loopback, ticket.port).use { s ->
            s.getOutputStream().write(
                ("$requestLine HTTP/1.1\r\nHost: x\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray()
            )
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    @Test
    fun `the full path - wait, allow, download, decrypt`() {
        val ticket = startServer()
        val (plain, sealed) = sealedBundle(ticket)
        val nonce = TransferTicket.newNonce()
        val client = TransferClient(ticket, nonce, sleep = {})

        assertEquals(TransferClient.State.Waiting, client.poll())
        assertTrue(events.contains("asked:$nonce"))

        server!!.approve(nonce)
        assertEquals(TransferClient.State.Preparing, client.poll())

        server!!.bundleReady(sealed)
        val ready = client.poll() as TransferClient.State.Ready
        assertEquals(sealed.length(), ready.length)

        val dest = File(tmp.root, "incoming.bin")
        client.download(dest, ready.length)
        val out = ByteArrayOutputStream()
        TransferBundle.open(dest.inputStream(), ticket.key, out)
        assertArrayEquals(plain, out.toByteArray())
        assertTrue(events.contains("delivered"))
    }

    @Test
    fun `an interrupted download resumes from where it stopped`() {
        val ticket = startServer()
        val (plain, sealed) = sealedBundle(ticket)
        val nonce = TransferTicket.newNonce()
        val client = TransferClient(ticket, nonce, sleep = {})
        client.poll()
        server!!.approve(nonce)
        server!!.bundleReady(sealed)

        // A previous attempt got the first 100,000 bytes before the Wi-Fi dropped.
        val dest = File(tmp.root, "partial.bin").apply { writeBytes(sealed.readBytes().copyOf(100_000)) }
        val progress = mutableListOf<Long>()
        client.download(dest, sealed.length(), onProgress = { progress += it })
        assertTrue("resumed rather than restarted", progress.first() > 100_000)
        val out = ByteArrayOutputStream()
        TransferBundle.open(dest.inputStream(), ticket.key, out)
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun `a device nobody approved gets nothing, and a denied one is told`() {
        val ticket = startServer()
        val (_, sealed) = sealedBundle(ticket)
        server!!.bundleReady(sealed)
        val stranger = TransferClient(ticket, TransferTicket.newNonce(), sleep = {})
        assertEquals(TransferClient.State.Waiting, stranger.poll())
        val response = raw(ticket, "GET ${ticket.pathPrefix}bundle", "X-Transfer-Nonce: ${TransferTicket.newNonce()}")
        assertTrue(response.startsWith("HTTP/1.1 202"))
        assertFalse(response.contains("OSTB"))

        val denied = TransferTicket.newNonce()
        val deniedClient = TransferClient(ticket, denied, sleep = {})
        deniedClient.poll()
        server!!.deny(denied)
        assertEquals(TransferClient.State.Denied, deniedClient.poll())
        try {
            deniedClient.download(File(tmp.root, "x"), sealed.length())
            fail()
        } catch (e: TransferBundle.Refused) {
            // expected
        }
    }

    @Test
    fun `only one device can ever be approved`() {
        val ticket = startServer()
        val first = TransferTicket.newNonce()
        val second = TransferTicket.newNonce()
        TransferClient(ticket, first, sleep = {}).poll()
        TransferClient(ticket, second, sleep = {}).poll()
        server!!.approve(first)
        server!!.approve(second)
        assertEquals(TransferServer.DeviceState.APPROVED, server!!.stateOf(first))
        assertEquals(TransferServer.DeviceState.WAITING, server!!.stateOf(second))
    }

    @Test
    fun `requests without a nonce, or to the wrong token, get nothing`() {
        val ticket = startServer()
        val (_, sealed) = sealedBundle(ticket)
        server!!.bundleReady(sealed)
        assertTrue(raw(ticket, "GET ${ticket.pathPrefix}bundle").startsWith("HTTP/1.1 400"))
        assertTrue(raw(ticket, "GET /t/AAAAAAAAAAAAAAAAAAAAAA/bundle", "X-Transfer-Nonce: ${TransferTicket.newNonce()}").startsWith("HTTP/1.1 404"))
        assertTrue(raw(ticket, "GET /favicon.ico").startsWith("HTTP/1.1 404"))
        assertTrue(raw(ticket, "GET ${ticket.pathPrefix}../../etc/passwd").startsWith("HTTP/1.1 404"))
        assertTrue(raw(ticket, "POST ${ticket.pathPrefix}bundle").startsWith("HTTP/1.1 405"))
    }

    @Test
    fun `probing for the token shuts the server down`() {
        val ticket = startServer()
        repeat(20) { runCatching { raw(ticket, "GET /t/guess$it/") } }
        Thread.sleep(100)
        assertFalse(server!!.isRunning)
        assertTrue(events.contains("stopped:TOO_MANY_WRONG_ADDRESSES"))
    }

    @Test
    fun `the landing page escapes what it prints and links the apk`() {
        val apk = tmp.newFile("app.apk").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        val ticket = startServer(apk)
        val page = raw(ticket, "GET ${ticket.pathPrefix}")
        assertTrue(page.startsWith("HTTP/1.1 200"))
        assertTrue(page.contains("Operations &lt;Sandbox&gt;"))
        assertFalse(page.contains("<Sandbox>"))
        assertTrue(page.contains("href=\"app.apk\""))

        val download = raw(ticket, "GET ${ticket.pathPrefix}app.apk")
        assertTrue(download.startsWith("HTTP/1.1 200"))
        assertTrue(download.contains("application/vnd.android.package-archive"))
        assertTrue(download.contains("Content-Length: 5000"))
        assertTrue(events.contains("apk"))

        val ranged = raw(ticket, "GET ${ticket.pathPrefix}app.apk", "Range: bytes=4990-")
        assertTrue(ranged.startsWith("HTTP/1.1 206"))
        assertTrue(ranged.contains("Content-Range: bytes 4990-4999/5000"))
    }

    @Test
    fun `an expired transfer answers nothing and stops`() {
        val ticket = startServer(ttl = 60_000)
        now.addAndGet(61_000)
        val response = runCatching { raw(ticket, "GET ${ticket.pathPrefix}") }.getOrDefault("")
        assertTrue(response.isEmpty() || response.startsWith("HTTP/1.1 410"))
        Thread.sleep(700)
        assertFalse(server!!.isRunning)
        assertTrue(events.contains("stopped:EXPIRED"))
    }

    @Test
    fun `a leaked code is worth nothing once the screen closes`() {
        val ticket = startServer()
        server!!.stop()
        val client = TransferClient(ticket, TransferTicket.newNonce(), connectTimeoutMs = 1000, sleep = {})
        assertTrue(client.poll() is TransferClient.State.Unreachable)
        try {
            client.download(File(tmp.root, "y"), 100, maxAttempts = 2)
            fail()
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `range parsing`() {
        assertEquals(HttpRange.Slice(10, 100), HttpRange.parse("bytes=10-", 100))
        assertEquals(HttpRange.Slice(10, 21), HttpRange.parse("bytes=10-20", 100))
        assertEquals(HttpRange.Slice(90, 100), HttpRange.parse("bytes=-10", 100))
        assertEquals(HttpRange.Slice(10, 100), HttpRange.parse("bytes=10-500", 100))
        assertEquals(HttpRange.Unsatisfiable, HttpRange.parse("bytes=100-", 100))
        assertNull(HttpRange.parse("bytes=0-1,5-6", 100))
        assertNull(HttpRange.parse("items=0-1", 100))
        assertNull(HttpRange.parse("bytes=20-10", 100))
    }
}
