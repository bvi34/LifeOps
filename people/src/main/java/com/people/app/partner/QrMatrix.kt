package com.people.app.partner

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A pairing code as a grid of black and white squares, with no Android in it.
 *
 * Drawing lives in `ui/partner/QrCode` and is a dozen lines of bitmap plumbing; *encoding* is the
 * part with decisions in it, and it is here so those decisions can be tested against a real decoder
 * rather than eyeballed on a screenshot. The test that matters — encode an invite, decode the grid,
 * get the same invite back — is not one you can write against a `@Composable`.
 *
 * zxing's core is plain Java with no Android dependency, which is what makes this possible; the
 * Android half of zxing is used only by the scanner.
 */
object QrMatrix {

    /**
     * A square grid, row-major, `true` where a module is dark.
     *
     * Array equality is identity in Kotlin, so [equals] and [hashCode] are written out rather than
     * generated — a data class here would quietly make two identical codes unequal, which is exactly
     * the sort of thing a caching `remember` key is built on.
     */
    class Matrix(val size: Int, private val modules: BooleanArray) {

        fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]

        override fun equals(other: Any?): Boolean =
            other is Matrix && other.size == size && other.modules.contentEquals(modules)

        override fun hashCode(): Int = 31 * size + modules.contentHashCode()
    }

    /**
     * Encode [text], one grid cell per QR module.
     *
     * Asking zxing for a `0 x 0` image is asking for the natural size: it renders at one pixel per
     * module rather than scaling to a requested pixel size. That keeps the result exact — every
     * module the same number of cells — and leaves the scaling to the view, which can do it with no
     * interpolation and get crisp squares at any size.
     *
     * Error correction stays at the default M. A pairing code is held up on a screen a few inches
     * from another phone; it is not printed on the side of a van. Spending a third of the capacity
     * on correction for damage that cannot happen would only make the modules smaller and the scan
     * slower.
     *
     * Null when the payload is too large for any QR version — unreachable for our fixed fields, but
     * the caller has a text fallback either way and a thrown exception here would take a dialog with
     * it.
     */
    fun encode(text: String): Matrix? = runCatching {
        val bits = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                // In modules, not pixels. One is below the spec's four, and deliberately: the code
                // is drawn on its own white card, so the card supplies the rest of the quiet zone.
                EncodeHintType.MARGIN to 1
            )
        )
        val size = bits.width
        val modules = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                modules[y * size + x] = bits.get(x, y)
            }
        }
        Matrix(size, modules)
    }.getOrNull()
}
