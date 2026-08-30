package com.people.app.ui.partner

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.people.app.partner.QrMatrix

/**
 * A pairing code, drawn.
 *
 * Deliberately **not** themed. Every other surface in this suite follows the sandbox's theme, and
 * this one must not: a QR code is read by a camera rather than by a person, and a dark-mode code in
 * the app's accent colours is a code that half the phones in the room will fail to decode. So it is
 * black on white, inside its own white card, whatever the rest of the screen is doing.
 *
 * The bitmap is remembered against the payload rather than rebuilt on every recomposition — this
 * sits in a dialog beside a text field, which recomposes on every keystroke.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, sizeDp: Int = 220) {
    val bitmap = remember(text) { QrMatrix.encode(text)?.toBitmap() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            // Only reachable if a payload outgrew every QR version, which our fixed fields cannot.
            // Say so plainly rather than leaving a white square that looks like a code the other
            // camera is simply failing to read.
            Text(
                "This code could not be drawn — use the pairing text below instead.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black,
                modifier = Modifier.size(sizeDp.dp)
            )
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Pairing QR code",
                // No smoothing. Interpolating the edges is how a crisp matrix becomes a blurry one
                // that scanners hesitate over.
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(sizeDp.dp)
            )
        }
    }
}

/** One bitmap pixel per module; the view scales it up with no filtering. */
private fun QrMatrix.Matrix.toBitmap(): Bitmap {
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (isDark(x, y)) BLACK else WHITE
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
