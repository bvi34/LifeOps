package com.people.app.partner

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing code, read back by a decoder rather than looked at.
 *
 * This is the test the drawing code cannot have: it puts a real invite through the real encoder,
 * hands the grid to zxing's own reader, and insists the invite that comes out is the one that went
 * in. If the payload ever outgrows what a code can carry, or the margin is trimmed to the point
 * where a decoder loses the finder pattern, this is what says so.
 */
class QrMatrixTest {

    private val invite = PartnerInvite(
        instanceId = "3f1c9a2e-7b4d-4f52-9c31-8de5a7104b6f",
        displayName = "Marta",
        personKey = "9b8c7d6e-5f4a-3b2c-1d0e-9f8a7b6c5d4e",
        secret = PairSecret.mint(),
        issuedAt = 1_800_000_000_000L
    )

    @Test
    fun `a pairing code scans back to the invite that made it`() {
        val text = PartnerInviteCodec.encode(invite)

        val decoded = scan(QrMatrix.encode(text)!!)

        assertEquals(text, decoded)
        assertEquals(invite, PartnerInviteCodec.decode(decoded))
    }

    @Test
    fun `a long display name still fits a code that scans`() {
        // The one field a person controls, at a length nobody sensible would type.
        val text = PartnerInviteCodec.encode(invite.copy(displayName = "Marta ".repeat(20).trim()))

        assertEquals(text, scan(QrMatrix.encode(text)!!))
    }

    @Test
    fun `the grid is square and quiet-zoned`() {
        val matrix = QrMatrix.encode(PartnerInviteCodec.encode(invite))!!

        assertTrue(matrix.size > 20)
        // A one-module quiet zone all the way round: the corners must be light.
        assertTrue(!matrix.isDark(0, 0))
        assertTrue(!matrix.isDark(matrix.size - 1, matrix.size - 1))
    }

    @Test
    fun `two codes for the same payload are equal, so the view can cache on them`() {
        val text = PartnerInviteCodec.encode(invite)

        assertEquals(QrMatrix.encode(text), QrMatrix.encode(text))
        assertEquals(QrMatrix.encode(text).hashCode(), QrMatrix.encode(text).hashCode())
    }

    /** Blow the grid up the way a screen does, then read it the way a camera would. */
    private fun scan(matrix: QrMatrix.Matrix, scale: Int = 8): String {
        val source = GridLuminanceSource(matrix, scale)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return QRCodeReader()
            .decode(bitmap, mapOf(DecodeHintType.CHARACTER_SET to "UTF-8"))
            .text
    }

    /** The grid as greyscale pixels: 0 is black, 255 is white — zxing's convention. */
    private class GridLuminanceSource(
        private val matrix: QrMatrix.Matrix,
        private val scale: Int
    ) : LuminanceSource(matrix.size * scale, matrix.size * scale) {

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) {
                out[x] = if (matrix.isDark(x / scale, y / scale)) 0 else 255.toByte()
            }
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                getRow(y, ByteArray(width)).copyInto(out, y * width)
            }
            return out
        }
    }
}
