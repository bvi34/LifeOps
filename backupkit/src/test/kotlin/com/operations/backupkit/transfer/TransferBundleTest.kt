package com.operations.backupkit.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import kotlin.random.Random

class TransferBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun plainFile(size: Int): File =
        tmp.newFile().apply { writeBytes(Random(size).nextBytes(size)) }

    private fun seal(plain: File, chunk: Int = 1000): ByteArray =
        ByteArrayOutputStream().also { TransferBundle.seal(plain, key, versionCode = 42, out = it, chunkSize = chunk) }.toByteArray()

    private fun open(sealed: ByteArray, withKey: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { TransferBundle.open(sealed.inputStream(), withKey, it) }.toByteArray()

    @Test
    fun `round trip across chunk boundaries`() {
        for (size in listOf(0, 1, 999, 1000, 1001, 5000, 12345)) {
            val plain = plainFile(size)
            val sealed = seal(plain)
            val header = TransferBundle.readHeader(sealed.inputStream())
            assertEquals("sealed length is known up front ($size)", header.sealedLength, sealed.size.toLong())
            assertEquals(42L, header.versionCode)
            assertArrayEquals(plain.readBytes(), open(sealed))
        }
    }

    @Test
    fun `the wrong key is refused`() {
        val sealed = seal(plainFile(3000))
        assertRefused { open(sealed, ByteArray(32)) }
    }

    @Test
    fun `a flipped bit anywhere is refused`() {
        val sealed = seal(plainFile(3000))
        for (position in listOf(10, TransferBundle.HEADER_BYTES + 5, sealed.size / 2, sealed.size - 1)) {
            val damaged = sealed.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertRefused { open(damaged) }
        }
    }

    @Test
    fun `a truncated bundle is refused, even on a chunk boundary`() {
        val sealed = seal(plainFile(3000))
        // Cut after the first full chunk: the header still says three, and chunk 0 isn't marked last.
        val oneChunk = TransferBundle.HEADER_BYTES + 1000 + 16
        assertRefused { open(sealed.copyOf(oneChunk)) }
        assertRefused { open(sealed.copyOf(sealed.size - 1)) }
    }

    @Test
    fun `trailing bytes are refused`() {
        val sealed = seal(plainFile(1500))
        assertRefused { open(sealed + byteArrayOf(0)) }
    }

    @Test
    fun `chunks cannot be reordered`() {
        val sealed = seal(plainFile(2000))
        val h = TransferBundle.HEADER_BYTES
        val c = 1016
        val swapped = sealed.copyOfRange(0, h) + sealed.copyOfRange(h + c, h + 2 * c) + sealed.copyOfRange(h, h + c)
        assertRefused { open(swapped) }
    }

    @Test
    fun `something that is not a bundle says so`() {
        try {
            open("PK\u0003\u0004 a zip file, not a bundle, with some more bytes to fill the header up".toByteArray())
            fail()
        } catch (e: TransferBundle.Refused) {
            assertTrue(e.message!!.contains("isn't an Operations Sandbox transfer"))
        }
    }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected the bundle to be refused")
        } catch (e: TransferBundle.Refused) {
            // expected
        }
    }
}
