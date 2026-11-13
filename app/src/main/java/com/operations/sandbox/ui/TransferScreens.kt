@file:OptIn(ExperimentalMaterial3Api::class)

package com.operations.sandbox.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.operations.backupkit.transfer.TransferServer
import com.operations.sandbox.BackupCenter
import com.operations.sandbox.transfer.TransferCaptureActivity
import com.operations.sandbox.transfer.TransferHost
import com.operations.sandbox.transfer.TransferReceiver
import kotlinx.coroutines.delay

/**
 * "Transfer to new device", on the old phone.
 *
 * Gated behind the phone's own screen lock, because what it hands over is everything in the suite.
 * A phone with no screen lock is refused outright: there, "unlocked" only means somebody is holding
 * it. While the code is showing:
 *
 *  - the screen is kept on, because turning it off closes the transfer;
 *  - screenshots and the recents thumbnail are blocked, because the code carries the key;
 *  - leaving the screen, the app going to the background, or the screen turning off all close the
 *    server, and the code stops working.
 */
@Composable
fun TransferOutScreen(center: BackupCenter, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember { TransferHost(context, center, scope) }
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    val hasScreenLock = keyguard?.isDeviceSecure == true

    val unlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) host.start()
    }

    // Closed on leaving the screen, and on ON_STOP: backgrounding the app or turning the screen off.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) host.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            host.stop()
        }
    }

    val serving = host.phase as? TransferHost.Phase.Serving
    val view = LocalView.current
    DisposableEffect(serving != null) {
        val window = context.findActivity()?.window
        if (serving != null) {
            view.keepScreenOn = true
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    TransferScaffold("Transfer to new device", onBack) {
        when (val phase = host.phase) {
            TransferHost.Phase.Idle, is TransferHost.Phase.Closed -> {
                SectionCard(
                    title = "Move the suite to a new phone",
                    subtitle = "This phone shows a code. The new phone scans it with its camera to install the app, " +
                        "then scans it again from inside the app to bring everything across. Both phones need to " +
                        "be on the same Wi-Fi, or the new phone can join this phone's hotspot."
                ) {
                    Bullet("The code works for ten minutes, and only while this screen stays open.")
                    Bullet("Nothing is sent until you tap Allow here, after checking that both phones show the same six digits.")
                    Bullet("Secrets stays locked under your master passphrase. You'll type it on the new phone.")
                    Spacer(Modifier.height(12.dp))
                    if (phase is TransferHost.Phase.Closed) {
                        Text(phase.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                    }
                    if (!hasScreenLock) {
                        Text(
                            "Set a screen lock on this phone first. This screen hands over everything in the suite, " +
                                "so it needs proof that the person holding the phone is you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Button(
                            onClick = {
                                keyguard?.createConfirmDeviceCredentialIntent(
                                    "Transfer to new device",
                                    "Unlock to show the transfer code"
                                )?.let(unlock::launch)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (phase is TransferHost.Phase.Closed) "Start a new transfer" else "Unlock and show code") }
                    }
                }
            }

            is TransferHost.Phase.Serving -> ServingContent(host, phase)
        }
    }
}

@Composable
private fun ServingContent(host: TransferHost, phase: TransferHost.Phase.Serving) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((phase.ticket.expiresAt - now) / 1000).coerceAtLeast(0)
    val qr = remember(phase.ticket) { qrBitmap(phase.ticket.url()) }

    SectionCard(
        title = "Scan this with the new phone",
        subtitle = "First with its camera app to install, then from \"Restore from old phone\" inside the app."
    ) {
        qr?.let {
            Box(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(bitmap = it.asImageBitmap(), contentDescription = "Transfer code", modifier = Modifier.size(280.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Closes in %d:%02d. Keep this screen open.".format(left / 60, left % 60),
            style = MaterialTheme.typography.bodyMedium
        )
        host.bundleStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!host.bundleReady && host.bundleStatus?.startsWith("Preparing") == true) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (!host.apkAvailable) {
            Spacer(Modifier.height(8.dp))
            Text(
                "This phone's copy of the app can't be served (it was installed in parts), so install it on the new " +
                    "phone from the release page instead, then scan from inside the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    host.requests.forEach { request ->
        SectionCard(
            title = when (request.state) {
                TransferServer.DeviceState.WAITING -> "A phone is asking for your data"
                TransferServer.DeviceState.APPROVED -> "Sending to this phone"
                TransferServer.DeviceState.DENIED -> "Declined"
            },
            subtitle = "Only allow it if the new phone shows exactly this code."
        ) {
            Text(
                request.code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            when (request.state) {
                TransferServer.DeviceState.WAITING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { host.deny(request.nonce) }, modifier = Modifier.weight(1f)) { Text("Deny") }
                    Button(onClick = { host.approve(request.nonce) }, modifier = Modifier.weight(1f)) { Text("Allow") }
                }
                TransferServer.DeviceState.APPROVED -> {
                    val total = host.totalBytes
                    if (host.delivered) {
                        Text("Sent. Finish on the new phone, then close this screen.", style = MaterialTheme.typography.bodyMedium)
                    } else if (total > 0 && host.sentBytes > 0) {
                        LinearProgressIndicator(progress = { (host.sentBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("${megabytes(host.sentBytes)} of ${megabytes(total)}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Waiting for the new phone to start downloading…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TransferServer.DeviceState.DENIED -> Unit
            }
        }
    }

    OutlinedButton(onClick = { host.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop transfer") }
    Text(
        "The app download isn't signed by a server, so only start this on Wi-Fi you trust. Your data is encrypted " +
            "with a key that is only in the code, and nobody gets it without your Allow.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * "Restore from old phone", on the new one.
 */
@Composable
fun TransferInScreen(center: BackupCenter, onBack: () -> Unit, onStartFresh: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val receiver = remember { TransferReceiver(context, center, scope) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> receiver.onScanned(result.contents) }

    DisposableEffect(Unit) { onDispose { if (receiver.busy) receiver.cancel() } }

    // Keep the screen on while data is moving: a phone that sleeps mid-download pauses its Wi-Fi.
    val view = LocalView.current
    DisposableEffect(receiver.busy) {
        view.keepScreenOn = receiver.busy
        onDispose { view.keepScreenOn = false }
    }

    TransferScaffold("Restore from old phone", onBack) {
        when (val phase = receiver.phase) {
            TransferReceiver.Phase.Idle, is TransferReceiver.Phase.Failed -> SectionCard(
                title = "Bring everything across",
                subtitle = "On the old phone, open the suite's Settings → Backups → Transfer to new device. " +
                    "Keep both phones on the same Wi-Fi, then scan the code it shows."
            ) {
                if (phase is TransferReceiver.Phase.Failed) {
                    Text(phase.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                Button(
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setCaptureActivity(TransferCaptureActivity::class.java)
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                                .setPrompt("Scan the code on the old phone")
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (phase is TransferReceiver.Phase.Failed) "Scan again" else "Scan code") }
                if (onStartFresh != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onStartFresh, modifier = Modifier.fillMaxWidth()) { Text("Start fresh instead") }
                }
            }

            is TransferReceiver.Phase.AwaitingApproval -> SectionCard(
                title = "Check the code on the old phone",
                subtitle = "If the old phone shows the same digits, tap Allow there. If they differ, tap Deny."
            ) {
                Text(phase.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 36.sp)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    if (phase.preparing) "Allowed. The old phone is still packing your data…" else "Waiting for Allow…",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = receiver::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }

            is TransferReceiver.Phase.Downloading -> SectionCard("Receiving your data") {
                val fraction = if (phase.total > 0) phase.received.toFloat() / phase.total else 0f
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("${megabytes(phase.received)} of ${megabytes(phase.total)}. Keep both screens on.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = receiver::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }

            TransferReceiver.Phase.Verifying -> SectionCard("Checking what arrived") {
                Text("Making sure every byte matches before anything on this phone changes.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            TransferReceiver.Phase.Restoring -> SectionCard("Restoring") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            is TransferReceiver.Phase.Done -> SectionCard(
                title = "Everything is across",
                subtitle = "Restored: ${phase.restored.joinToString { it.defaultDisplayName }}."
            ) {
                Text(
                    "Close Operations Sandbox completely (swipe it away from Recents) and open it again so the restored " +
                        "data loads. Then open Secrets and unlock it with your master passphrase.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
private fun TransferScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

private fun qrBitmap(text: String, size: Int = 720): Bitmap? = runCatching {
    val bits = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
    )
    val pixels = IntArray(size * size) { i -> if (bits[i % size, i / size]) BLACK else WHITE }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}.getOrNull()

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
