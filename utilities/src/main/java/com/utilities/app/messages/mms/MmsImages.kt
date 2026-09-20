package com.utilities.app.messages.mms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.utilities.app.messages.pdu.MmsBudget
import com.utilities.app.messages.pdu.MmsPart
import com.utilities.app.messages.pdu.Wsp
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Making a photograph small enough to send, and small enough to draw.
 *
 * ## Why every picture has to be re-encoded
 *
 * Carriers cap a picture message at about 300KB. A phone camera produces four megabytes a shot. So
 * there is no path where the file somebody picked is the file that gets sent, and the only question
 * is how it is shrunk — which [MmsBudget] answers as arithmetic and this file carries out on pixels.
 *
 * The ladder is tried in order and the first rung that fits wins. Scaling comes before quality
 * because halving the dimensions removes three quarters of the pixels and is nearly invisible on a
 * phone screen, while JPEG quality under about 60 puts artefacts around text — and a photograph of a
 * receipt or a screenshot is as common a thing to send as a face.
 *
 * ## What is not re-encoded
 *
 * An animated GIF that already fits. Re-encoding one to JPEG produces a still frame, which is not a
 * smaller version of the thing somebody chose to send — it is a different thing. If it does not fit,
 * it is refused rather than silently flattened.
 */
object MmsImages {

    /** Nothing above this is decoded for display. A thread does not need full-resolution bitmaps. */
    const val PREVIEW_MAX_PX = 1280

    /**
     * Turn something the household picked out of a photo picker into a part that will fit.
     *
     * Returns null when it cannot be made to fit at all — a budget below
     * [MmsBudget.MINIMUM_USEFUL_BYTES], an animated image too big to pass through, or a file the
     * platform cannot decode. The caller says so; it does not send a mosaic.
     */
    fun attach(context: Context, uri: Uri, budgetBytes: Int, index: Int): MmsPart? {
        if (budgetBytes < MmsBudget.MINIMUM_USEFUL_BYTES) return null
        val original = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (original.isEmpty()) return null

        val declared = context.contentResolver.getType(uri)?.lowercase().orEmpty()

        // Already small enough, and a format worth keeping exactly as it is.
        if (original.size <= budgetBytes && passesThrough(declared)) {
            return part(original, declared.ifBlank { "image/jpeg" }, index, extensionFor(declared))
        }

        // An animation that will not fit is refused rather than turned into a still frame.
        if (declared == "image/gif" && original.size > budgetBytes) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        MmsBudget.LADDER.forEach { rung ->
            val encoded = encode(original, bounds, rung.scale, rung.quality) ?: return@forEach
            if (encoded.size <= budgetBytes) {
                return part(encoded, "image/jpeg", index, "jpg")
            }
        }
        return null
    }

    /**
     * A bitmap for the screen, at a size worth drawing.
     *
     * Decoded with `inSampleSize` rather than at full resolution and scaled afterwards: a
     * twelve-megapixel photograph decoded whole is forty-eight megabytes of heap, and a thread
     * scrolling past six of them is an app the system kills.
     */
    fun preview(context: Context, uri: Uri, maxPx: Int = PREVIEW_MAX_PX): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    /** What the platform calls the file somebody picked, for the part's name. */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    }.getOrNull()

    /** The words of a message, as a part. */
    fun textPart(body: String): MmsPart = MmsPart(
        contentType = "text/plain",
        data = body.toByteArray(Charsets.UTF_8),
        name = TEXT_NAME,
        contentId = TEXT_NAME,
        contentLocation = TEXT_NAME,
        charset = Wsp.CHARSET_UTF_8
    )

    /**
     * Whether a format is worth sending untouched when it already fits.
     *
     * JPEG and PNG because re-encoding them gains nothing; GIF because re-encoding it loses the
     * animation. Anything else is decoded and re-encoded, which also normalises the dozen formats a
     * modern phone camera can produce into one every handset can display.
     */
    private fun passesThrough(mime: String): Boolean =
        mime == "image/jpeg" || mime == "image/png" || mime == "image/gif"

    private fun encode(source: ByteArray, bounds: BitmapFactory.Options, scale: Float, quality: Int): ByteArray? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(scale)
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        val wanted = scaled(bitmap, scale, options.inSampleSize)
        val out = ByteArrayOutputStream()
        val ok = runCatching { wanted.compress(Bitmap.CompressFormat.JPEG, quality, out) }.getOrDefault(false)
        if (wanted !== bitmap) wanted.recycle()
        bitmap.recycle()
        return if (ok) out.toByteArray() else null
    }

    /**
     * `inSampleSize` only halves, so it gets as close as it can from above and the remainder is done
     * by scaling the decoded bitmap. Doing all of it with `inSampleSize` would quantise every rung
     * of the ladder to a power of two and collapse three of them into one.
     */
    private fun sampleFor(scale: Float): Int {
        var sample = 1
        while (scale * sample * 2 <= 1f) sample *= 2
        return sample
    }

    private fun scaled(bitmap: Bitmap, scale: Float, alreadySampled: Int): Bitmap {
        val effective = scale * alreadySampled
        if (effective >= 0.999f) return bitmap
        val width = max(1, (bitmap.width * effective).roundToInt())
        val height = max(1, (bitmap.height * effective).roundToInt())
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return runCatching { Bitmap.createScaledBitmap(bitmap, width, height, true) }.getOrDefault(bitmap)
    }

    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxPx || height / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    private fun part(bytes: ByteArray, mime: String, index: Int, extension: String): MmsPart {
        val name = "image_$index.$extension"
        return MmsPart(
            contentType = mime,
            data = bytes,
            name = name,
            contentId = name,
            contentLocation = name
        )
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        else -> "jpg"
    }

    /** What the words are called inside a picture message. The layout part points at it by name. */
    const val TEXT_NAME = "text_0.txt"
}
