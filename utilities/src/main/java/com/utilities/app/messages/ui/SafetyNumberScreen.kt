package com.utilities.app.messages.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.utilities.app.messages.seal.SafetyNumber
import com.utilities.app.messages.seal.Sealing
import com.utilities.app.messages.seal.Trust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sixty digits, and the one thing they are for.
 *
 * Automatic key exchange means the first exchange could, in principle, have been intercepted — an
 * attacker between two phones handing each of them its own key. Nothing in the protocol can detect
 * that, because from the inside it looks exactly like a working conversation. What detects it is two
 * people comparing a number computed from **both** identity keys, because the attacker's version has
 * two different pairs in it.
 *
 * So this screen exists for the small number of people who will use it, and it is written for them:
 * the number is large, monospaced and grouped in fives so it can be read aloud down a phone line,
 * and there is a code to scan when the two people are in the same room. The explanation says what
 * the check is for in one sentence rather than in a paragraph about cryptography.
 *
 * ## What is deliberately not here
 *
 * A prompt. Nothing in this app nags anybody to verify a contact: the number of people for whom this
 * matters is small, and a security prompt that everybody dismisses trains everybody to dismiss
 * security prompts.
 */
@Composable
fun SafetyNumberScreen(
    address: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sealing = remember { Sealing(context) }
    var trust by remember(address) { mutableStateOf(sealing.trustFor(address)) }
    val number = remember(address, trust) { sealing.safetyNumber(address) }

    // Five thousand rounds of SHA-512 is a tenth of a second and has no business on the main thread.
    val code by produceState<Bitmap?>(initialValue = null, number) {
        value = number?.let { withContext(Dispatchers.Default) { qrCode(it) } }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TrustBadge(trust)
        Spacer(Modifier.height(16.dp))

        if (number == null) {
            Text(
                "There is nothing to compare yet. Utilities has not exchanged keys with $title — " +
                    "either they do not have this app, or nothing has been sent between you since " +
                    "they installed it.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
            return@Column
        }

        Text(
            "Compare these numbers with $title — read them aloud, or scan each other's code. If " +
                "they match, nobody was in the middle when your two phones first exchanged keys.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))

        code?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "This conversation's code",
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .padding(8.dp)
            )
            Spacer(Modifier.height(20.dp))
        }

        // Grouped in fives and set in a monospaced face, because the whole task is reading sixty
        // digits aloud without losing your place.
        Text(
            SafetyNumber.display(number),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
                lineHeight = 32.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(16.dp)
        )

        Spacer(Modifier.height(20.dp))

        if (trust == Trust.VERIFIED) {
            Text(
                "Marked as verified.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Button(onClick = {
                sealing.markVerified(address)
                trust = sealing.trustFor(address)
            }) {
                Text("They match")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Marking a conversation verified changes nothing about how it is encrypted — it " +
                "records that somebody checked, so that Utilities can tell you if the keys ever " +
                "change afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

/**
 * The state of a conversation's encryption, as one line.
 *
 * Three states and three different sentences, because "encrypted" on its own would be the overclaim
 * this whole design is trying not to make: an unverified session *is* encrypted and *cannot* rule
 * out somebody having been present at the start, and the badge says both.
 */
@Composable
fun TrustBadge(trust: Trust, compact: Boolean = false) {
    val (icon, label, colour) = when (trust) {
        Trust.NONE -> Triple(
            Icons.Filled.LockOpen,
            if (compact) "Not encrypted" else "Not encrypted",
            MaterialTheme.colorScheme.onSurfaceVariant
        )

        Trust.FIRST_USE -> Triple(
            Icons.Filled.Lock,
            if (compact) "Encrypted" else "Encrypted — not verified",
            MaterialTheme.colorScheme.primary
        )

        Trust.VERIFIED -> Triple(
            Icons.Filled.Verified,
            if (compact) "Verified" else "Encrypted and verified",
            MaterialTheme.colorScheme.primary
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(if (compact) 14.dp else 20.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = colour
        )
    }
}

/**
 * The number as a code.
 *
 * Both ends encode the *same* string — the safety number, which both sides compute identically — so
 * a scan is a comparison rather than a transfer. Nothing secret is in it and nothing is learned by
 * photographing somebody's screen; the code is a way of comparing sixty digits without reading them
 * aloud.
 */
private fun qrCode(number: String, size: Int = 512): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )
    val matrix = QRCodeWriter().encode("$QR_PREFIX$number", BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) BLACK else WHITE)
        }
    }
    bitmap
}.getOrNull()

/** So a code from some other app is not mistaken for one of ours. */
const val QR_PREFIX = "utilities-safety:"

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
