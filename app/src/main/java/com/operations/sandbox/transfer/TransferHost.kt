package com.operations.sandbox.transfer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.operations.backupkit.transfer.TransferBundle
import com.operations.backupkit.transfer.TransferServer
import com.operations.backupkit.transfer.TransferTicket
import com.operations.sandbox.BackupCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The old phone's half of "Transfer to new device".
 *
 * It owns one [TransferServer] for as long as the transfer screen is showing, and nothing it makes
 * outlives [stop]. That covers the socket, the sealed bundle in the cache, and the plaintext archive
 * that existed for a few seconds while it was being sealed. The screen calls [stop] on dispose and
 * on `ON_STOP`, which is also what turning the screen off produces.
 *
 * The QR code is shown at once, while the archive is still being written, because the first thing
 * the new phone needs is the APK. Installing it takes a minute, and the bundle is usually sealed
 * by the time anyone asks for it. A device that asks early gets "preparing" and keeps polling.
 */
@Stable
class TransferHost(
    context: Context,
    private val center: BackupCenter,
    private val scope: CoroutineScope
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    sealed interface Phase {
        object Idle : Phase
        data class Serving(val ticket: TransferTicket) : Phase
        data class Closed(val message: String) : Phase
    }

    /** A device that asked for the data, with the code its screen should be showing. */
    data class Request(val nonce: String, val code: String, val state: TransferServer.DeviceState)

    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set

    /** "Preparing your data…", "Ready", or what went wrong sealing it. */
    var bundleStatus by mutableStateOf<String?>(null)
        private set

    var bundleReady by mutableStateOf(false)
        private set

    var apkAvailable by mutableStateOf(false)
        private set

    val requests = mutableStateListOf<Request>()

    var sentBytes by mutableStateOf(0L)
        private set

    var totalBytes by mutableStateOf(0L)
        private set

    var delivered by mutableStateOf(false)
        private set

    private var server: TransferServer? = null
    private var prepareJob: Job? = null
    private val workDir: File get() = File(app.cacheDir, "transfer-out")

    /** Call only once the person has satisfied the device credential. */
    fun start() {
        if (phase is Phase.Serving) return
        val address = TransferNetwork.localAddress(app)
        if (address == null) {
            phase = Phase.Closed(
                "Connect this phone to Wi-Fi, or turn on its hotspot and join it from the new phone, then try again."
            )
            return
        }
        reset()
        workDir.deleteRecursively()
        workDir.mkdirs()

        val draft = TransferTicket.mint(address.hostAddress ?: return, port = 1, now = System.currentTimeMillis())
        val apk = installedApk()
        apkAvailable = apk != null
        val versionName = packageInfo()?.versionName ?: "?"
        val ticketHolder = arrayOfNulls<TransferTicket>(1)
        val listener = object : TransferServer.Listener {
            override fun onDeviceAsked(nonce: String) = onMain {
                val key = ticketHolder[0]?.key ?: return@onMain
                requests += Request(nonce, TransferTicket.confirmationCode(key, nonce), TransferServer.DeviceState.WAITING)
            }

            override fun onBundleProgress(sent: Long, total: Long) = onMain {
                sentBytes = sent
                totalBytes = total
            }

            override fun onBundleDelivered() = onMain { delivered = true }

            override fun onStopped(reason: TransferServer.StopReason) = onMain {
                if (phase is Phase.Serving) closeWith(describe(reason))
            }
        }
        val s = TransferServer(
            ticketToken = draft.token,
            expiresAt = draft.expiresAt,
            apk = apk?.let { TransferServer.ServedFile(it, "operations-sandbox-$versionName.apk", APK_TYPE) },
            landing = TransferServer.LandingPage("Operations Sandbox", versionName, apkSha256 = null),
            listener = listener
        )
        val port = runCatching { s.start(address) }.getOrElse {
            phase = Phase.Closed("Couldn't start the transfer on this network: ${it.message}")
            return
        }
        val ticket = draft.copy(port = port)
        ticketHolder[0] = ticket
        server = s
        phase = Phase.Serving(ticket)
        prepare(ticket)
    }

    fun approve(nonce: String) = decide(nonce, TransferServer.DeviceState.APPROVED)

    fun deny(nonce: String) = decide(nonce, TransferServer.DeviceState.DENIED)

    /** Close the server and delete everything this transfer wrote. Safe to call any number of times. */
    fun stop(message: String = "Transfer closed. The code no longer works.") {
        if (phase is Phase.Serving) closeWith(message)
    }

    private fun closeWith(message: String) {
        prepareJob?.cancel()
        server?.stop()
        server = null
        phase = Phase.Closed(if (delivered) "Your data was sent. Finish on the new phone." else message)
        // A plain thread, not the scope: this runs from the screen's onDispose, when the
        // composition's scope is already cancelled and would drop the work.
        val dir = workDir
        Thread { dir.deleteRecursively() }.start()
    }

    private fun decide(nonce: String, state: TransferServer.DeviceState) {
        val s = server ?: return
        if (state == TransferServer.DeviceState.APPROVED) s.approve(nonce) else s.deny(nonce)
        val actual = s.stateOf(nonce) ?: return
        val index = requests.indexOfFirst { it.nonce == nonce }
        if (index >= 0) requests[index] = requests[index].copy(state = actual)
        // Once one device has the go-ahead, the rest are declined, so a second phone sees a clear no
        // instead of waiting for an Allow that can't come.
        if (actual == TransferServer.DeviceState.APPROVED) {
            requests.filter { it.nonce != nonce && it.state == TransferServer.DeviceState.WAITING }
                .forEach { decide(it.nonce, TransferServer.DeviceState.DENIED) }
        }
    }

    private fun prepare(ticket: TransferTicket) {
        bundleStatus = "Preparing your data…"
        prepareJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val plain = File(workDir, "archive.zip")
                    val sealed = File(workDir, "bundle.bin")
                    try {
                        plain.outputStream().use { out ->
                            center.backup(center.contributors.map { it.appId }.toSet(), out)
                        }
                        sealed.outputStream().buffered().use { out ->
                            TransferBundle.seal(plain, ticket.key, versionCode(), out)
                        }
                    } finally {
                        plain.delete()
                    }
                    sealed
                }
            }
            result.fold(
                onSuccess = { sealed ->
                    if (phase !is Phase.Serving) {
                        // Stopped while the archive was being written: nothing may be left behind.
                        Thread { sealed.delete() }.start()
                    } else {
                        server?.bundleReady(sealed)
                        totalBytes = sealed.length()
                        bundleReady = true
                        bundleStatus = "Ready to send."
                    }
                },
                onFailure = { bundleStatus = "Couldn't prepare your data: ${it.message}" }
            )
        }
    }

    private fun reset() {
        requests.clear()
        sentBytes = 0
        totalBytes = 0
        delivered = false
        bundleReady = false
        bundleStatus = null
    }

    /**
     * This phone's own installed APK, the file the new phone should run. Null when it was installed
     * as split APKs (a Play-style install), which a browser can't install as one download.
     */
    private fun installedApk(): File? {
        val info = app.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return null
        return File(info.sourceDir).takeIf { it.canRead() }
    }

    private fun packageInfo() = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()

    private fun versionCode(): Long = packageInfo()?.longVersionCode ?: 0L

    private fun onMain(block: () -> Unit) {
        main.post { block() }
    }

    private fun describe(reason: TransferServer.StopReason): String = when (reason) {
        TransferServer.StopReason.CLOSED -> "Transfer closed. The code no longer works."
        TransferServer.StopReason.EXPIRED -> "The ten minutes ran out, and the code no longer works. Start again when the new phone is ready."
        TransferServer.StopReason.TOO_MANY_WRONG_ADDRESSES ->
            "Something on this network kept guessing at the transfer's address, so it was closed. Start a new one."
        TransferServer.StopReason.FAILED -> "The transfer stopped because the network changed. Start a new one."
    }

    companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"

        /**
         * Delete whatever a transfer left in the cache if the process died mid-way: a sealed
         * bundle, or a plaintext archive that was being sealed. Called on a fresh launch, when no
         * transfer can be running.
         */
        fun sweepLeftovers(context: Context) {
            val cache = context.applicationContext.cacheDir
            Thread {
                File(cache, "transfer-out").deleteRecursively()
                File(cache, "transfer-in").deleteRecursively()
            }.start()
        }

    }
}
