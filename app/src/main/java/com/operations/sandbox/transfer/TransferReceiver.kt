package com.operations.sandbox.transfer

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.operations.backupkit.AppId
import com.operations.backupkit.transfer.TransferBundle
import com.operations.backupkit.transfer.TransferClient
import com.operations.backupkit.transfer.TransferTicket
import com.operations.sandbox.BackupCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The new phone's half: scan, wait for **Allow**, download, check, then restore.
 *
 * Nothing touches the phone's data until [TransferBundle.open] has checked every chunk, the length
 * and the whole-archive checksum, and the build matches. Until then, everything lives in a cache
 * directory this deletes whatever happens. The restore itself is the same [BackupCenter.restore]
 * that "Restore from zip" uses.
 */
@Stable
class TransferReceiver(
    context: Context,
    private val center: BackupCenter,
    private val scope: CoroutineScope
) {
    private val app = context.applicationContext

    sealed interface Phase {
        object Idle : Phase
        /** Show [code] and ask the person to compare it and tap Allow on the old phone. */
        data class AwaitingApproval(val code: String, val preparing: Boolean) : Phase
        data class Downloading(val received: Long, val total: Long) : Phase
        object Verifying : Phase
        object Restoring : Phase
        data class Done(val restored: List<AppId>) : Phase
        data class Failed(val message: String) : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set

    val busy: Boolean
        get() = phase !is Phase.Idle && phase !is Phase.Done && phase !is Phase.Failed

    private var job: Job? = null
    @Volatile private var cancelled = false
    private val workDir: File get() = File(app.cacheDir, "transfer-in")

    fun onScanned(contents: String?) {
        if (busy) return
        val ticket = contents?.let(TransferTicket::parse)
        when {
            contents == null -> return
            ticket == null -> {
                phase = Phase.Failed("That isn't a transfer code. On the old phone, open Settings → Backups → Transfer to new device.")
                return
            }
            ticket.isExpired(System.currentTimeMillis()) -> {
                phase = Phase.Failed("That code has expired. Start a new transfer on the old phone.")
                return
            }
        }
        run(ticket)
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
        phase = Phase.Idle
        // A plain thread: this also runs from onDispose, when the scope is already cancelled.
        val dir = workDir
        Thread { dir.deleteRecursively() }.start()
    }

    private fun run(ticket: TransferTicket) {
        cancelled = false
        val nonce = TransferTicket.newNonce()
        val code = TransferTicket.confirmationCode(ticket.key, nonce)
        val client = TransferClient(ticket, nonce)
        phase = Phase.AwaitingApproval(code, preparing = false)
        job = scope.launch {
            val outcome = runCatching {
                val total = awaitReady(client, code, ticket)
                withContext(Dispatchers.IO) { receive(client, ticket, total) }
            }
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
            if (!isActive || cancelled) return@launch
            phase = outcome.fold(
                onSuccess = { restored ->
                    FirstRun.resolve(app)
                    Phase.Done(restored)
                },
                onFailure = { Phase.Failed(it.message ?: "The transfer failed.") }
            )
        }
    }

    /** Poll until the old phone says go. Returns the bundle's length. */
    private suspend fun awaitReady(client: TransferClient, code: String, ticket: TransferTicket): Long {
        var unreachable = 0
        while (true) {
            if (ticket.isExpired(System.currentTimeMillis())) {
                throw TransferBundle.Refused("The code expired before the old phone allowed it. Start a new transfer there.")
            }
            val state = withContext(Dispatchers.IO) { client.poll() }
            if (state !is TransferClient.State.Unreachable) unreachable = 0
            when (state) {
                TransferClient.State.Waiting -> phase = Phase.AwaitingApproval(code, preparing = false)
                TransferClient.State.Preparing -> phase = Phase.AwaitingApproval(code, preparing = true)
                TransferClient.State.Denied -> throw TransferBundle.Refused("The old phone declined this phone.")
                TransferClient.State.Gone -> throw TransferBundle.Refused("The old phone closed the transfer. Start a new one and scan again.")
                is TransferClient.State.Ready -> return state.length
                is TransferClient.State.Unreachable -> {
                    if (++unreachable >= MAX_UNREACHABLE_POLLS) throw TransferBundle.Refused(state.reason)
                }
            }
            delay(POLL_MS)
        }
    }

    private suspend fun receive(client: TransferClient, ticket: TransferTicket, total: Long): List<AppId> {
        val dir = workDir.apply { deleteRecursively(); mkdirs() }
        val sealed = File(dir, "bundle.bin")
        val plain = File(dir, "archive.zip")
        try {
            phase = Phase.Downloading(0, total)
            client.download(sealed, total, onProgress = { got -> phase = Phase.Downloading(got, total) }, cancelled = { cancelled })

            phase = Phase.Verifying
            val header = sealed.inputStream().use { TransferBundle.readHeader(it) }
            val mine = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)
            if (header.versionCode != mine) {
                throw TransferBundle.Refused(
                    "The old phone is running a different build of the suite (build ${header.versionCode}; this phone has $mine). " +
                        "Install the app from the old phone's code first, then try again."
                )
            }
            plain.outputStream().buffered().use { out -> sealed.inputStream().buffered().use { TransferBundle.open(it, ticket.key, out) } }
            sealed.delete()

            val manifest = plain.inputStream().use { center.peek(it) }
                ?: throw TransferBundle.Refused("The data arrived but isn't a readable backup. Nothing was restored.")
            val present = manifest.apps.mapNotNull { AppId.fromKey(it.appId) }.toSet()
            val known = center.contributors.map { it.appId }.toSet()
            val toRestore = present intersect known
            if (toRestore.isEmpty()) throw TransferBundle.Refused("The old phone sent no apps this version can restore.")

            phase = Phase.Restoring
            plain.inputStream().use { center.restore(toRestore, it) }
            return toRestore.sortedBy { it.ordinal }
        } finally {
            sealed.delete()
            plain.delete()
        }
    }

    private companion object {
        const val POLL_MS = 1_500L
        /** About fifteen seconds of silence before giving up while waiting for Allow. */
        const val MAX_UNREACHABLE_POLLS = 10
    }
}
