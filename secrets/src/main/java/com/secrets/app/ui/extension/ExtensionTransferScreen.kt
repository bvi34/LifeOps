package com.secrets.app.ui.extension

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.operations.vaultkit.ExtensionTransfer
import com.secrets.app.data.VaultStore
import com.secrets.app.ui.scan.SecureCaptureActivity
import kotlinx.coroutines.delay

/** A physical, encrypted vault hand-off to the Edge extension; no network path exists here. */
@Composable
fun ExtensionTransferScreen(store: VaultStore, onDone: () -> Unit) {
    var request by remember { mutableStateOf<ExtensionTransfer.Request?>(null) }
    var frames by remember { mutableStateOf(emptyList<String>()) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents?.let(ExtensionTransfer::parseRequest)
        val vault = store.sealedVaultForExtension()
        when {
            scanned == null -> error = "That is not an Edge transfer code. Start a new transfer in the extension."
            vault == null -> error = "Unlock the vault before sending it to an extension."
            else -> {
                request = scanned
                frames = ExtensionTransfer.frames(scanned, vault)
                frameIndex = 0
                error = null
            }
        }
    }

    LaunchedEffect(frames, frameIndex) {
        if (frames.size > 1) {
            delay(1_300)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Connect Edge", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The extension shows a one-time QR code. Scan it, then let the extension scan every " +
                "code shown here. Your vault stays encrypted in transit and the extension still " +
                "requires the master passphrase to open it.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (frames.isEmpty()) {
            Button(onClick = {
                scanner.launch(ScanOptions().setCaptureActivity(SecureCaptureActivity::class.java).setPrompt("Scan Edge transfer code"))
            }) { Text("Scan Edge QR code") }
        } else {
            val current = frames[frameIndex]
            qrBitmap(current)?.let { bitmap ->
                Image(bitmap.asImageBitmap(), "Encrypted vault transfer QR", Modifier.fillMaxWidth().height(360.dp))
            } ?: Text("This vault is too large for a QR frame. Cancel and try again.")
            Text("Sending encrypted frame ${frameIndex + 1} of ${frames.size}")
            OutlinedButton(onClick = { frameIndex = (frameIndex + 1) % frames.size }) { Text("Show next code") }
            OutlinedButton(onClick = { frames = emptyList(); request = null; frameIndex = 0 }) { Text("Cancel transfer") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onDone) { Text("Done") }
    }
}

private fun qrBitmap(text: String, size: Int = 900): Bitmap? = runCatching {
    val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    ))
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (bits[x, y]) BLACK else WHITE)
    }
}.getOrNull()

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
