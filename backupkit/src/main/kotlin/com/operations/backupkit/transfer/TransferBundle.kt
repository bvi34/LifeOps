package com.operations.backupkit.transfer

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The suite's ordinary backup archive, sealed for the trip from one phone to the next.
 *
 * ## Format
 *
 * A fixed header, then the archive in chunks, each encrypted with AES-256-GCM:
 *
 * ```
 * "OSTB" | version:u8 | chunkSize:i32 | plainLength:i64 | sha256(plain):32 | versionCode:i64 | noncePrefix:8
 * chunk 0 | chunk 1 | … | chunk n-1          (each = ciphertext + 16-byte tag)
 * ```
 *
 * Each chunk's nonce is the prefix followed by its index. Its additional data is the whole header,
 * then the index, then a flag for whether it is the last chunk. So a chunk can't be moved,
 * repeated, dropped from the end, or paired with a different header without its tag failing.
 *
 * Chunks rather than one GCM pass for two reasons. The ciphertext has a known length before the
 * first byte is sent, so a dropped Wi-Fi link can resume from a byte offset. And the new phone can
 * check each megabyte as it arrives, instead of holding an unauthenticated archive until the last
 * byte.
 *
 * ## What is checked before anything is restored
 *
 * Opening a bundle checks each chunk's tag, that the chunk count and plaintext length match the
 * header, that nothing follows the last chunk, and that the SHA-256 of the whole plaintext matches
 * the header. The plaintext is written to a scratch file the caller owns. Only once [open] has
 * returned does the caller hand that file to the restore, so a failure at any point leaves the
 * phone's data exactly as it was.
 *
 * The [Header.versionCode] lets the new phone refuse a bundle from a different build of the suite.
 * The APK it installed came from the same phone at the same moment, so a mismatch means the person
 * installed something else.
 *
 * The Secrets app's slice is already sealed inside the archive under the household's master
 * passphrase. This layer does not change that, so the vault still needs the passphrase on the new
 * phone, exactly as after a restore from a zip.
 */
object TransferBundle {

    private val MAGIC = byteArrayOf('O'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte())
    private const val VERSION: Byte = 1
    private const val TAG_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    const val HEADER_BYTES = 4 + 1 + 4 + 8 + 32 + 8 + NONCE_PREFIX_BYTES
    const val DEFAULT_CHUNK_SIZE = 1 shl 20

    data class Header(
        val chunkSize: Int,
        val plainLength: Long,
        val sha256: ByteArray,
        val versionCode: Long,
        val noncePrefix: ByteArray
    ) {
        val chunkCount: Long
            get() = if (plainLength == 0L) 1 else (plainLength + chunkSize - 1) / chunkSize

        /** The exact size of the sealed bundle, header included. */
        val sealedLength: Long get() = HEADER_BYTES + plainLength + chunkCount * TAG_BYTES

        fun encode(): ByteArray = ByteArrayOutputStream(HEADER_BYTES).also { bytes ->
            DataOutputStream(bytes).run {
                write(MAGIC)
                writeByte(VERSION.toInt())
                writeInt(chunkSize)
                writeLong(plainLength)
                write(sha256)
                writeLong(versionCode)
                write(noncePrefix)
            }
        }.toByteArray()

        override fun equals(other: Any?): Boolean = other is Header && encode().contentEquals(other.encode())
        override fun hashCode(): Int = encode().contentHashCode()
    }

    /** Why a bundle was refused, in words fit for the screen. Never carries key material. */
    class Refused(message: String) : Exception(message)

    /** Seal [plain] into [out] under [key]. [out] is not closed. */
    fun seal(
        plain: File,
        key: ByteArray,
        versionCode: Long,
        out: OutputStream,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        random: SecureRandom = SecureRandom()
    ): Header {
        require(key.size == 32) { "A transfer key is 32 bytes" }
        require(chunkSize > 0)
        val header = Header(
            chunkSize = chunkSize,
            plainLength = plain.length(),
            sha256 = sha256(plain),
            versionCode = versionCode,
            noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        )
        val headerBytes = header.encode()
        out.write(headerBytes)
        val secret = SecretKeySpec(key, "AES")
        val buffer = ByteArray(chunkSize)
        plain.inputStream().buffered().use { input ->
            for (index in 0 until header.chunkCount) {
                val read = readFully(input, buffer)
                val last = index == header.chunkCount - 1
                val cipher = cipher(Cipher.ENCRYPT_MODE, secret, header, headerBytes, index, last)
                out.write(cipher.doFinal(buffer, 0, read))
            }
        }
        out.flush()
        return header
    }

    /** Read just the header, so the new phone can check the build before downloading the rest. */
    fun readHeader(input: InputStream): Header {
        val bytes = ByteArray(HEADER_BYTES)
        if (readFully(input, bytes) != HEADER_BYTES) throw Refused("The transfer ended before it began.")
        return decodeHeader(bytes)
    }

    /**
     * Check and decrypt [sealed] into [plainOut], and return its header.
     *
     * Throws [Refused] on any failure. [plainOut] may hold a partial plaintext by then, which is
     * why the caller writes it to a scratch file and only restores from that file once this has
     * returned.
     */
    fun open(sealed: InputStream, key: ByteArray, plainOut: OutputStream): Header {
        if (key.size != 32) throw Refused("That code doesn't carry a valid key.")
        val headerBytes = ByteArray(HEADER_BYTES)
        if (readFully(sealed, headerBytes) != HEADER_BYTES) throw Refused("The transfer ended before it began.")
        val header = decodeHeader(headerBytes)
        if (header.chunkSize <= 0 || header.chunkSize > 64 * DEFAULT_CHUNK_SIZE || header.plainLength < 0) {
            throw Refused("The transfer's header is damaged.")
        }
        val secret = SecretKeySpec(key, "AES")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(header.chunkSize + TAG_BYTES)
        var remaining = header.plainLength
        for (index in 0 until header.chunkCount) {
            val plainSize = minOf(remaining, header.chunkSize.toLong()).toInt()
            val sealedSize = plainSize + TAG_BYTES
            if (readFully(sealed, buffer, sealedSize) != sealedSize) {
                throw Refused("The transfer stopped part-way through. Try again.")
            }
            val last = index == header.chunkCount - 1
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, secret, header, headerBytes, index, last).doFinal(buffer, 0, sealedSize)
            } catch (e: AEADBadTagException) {
                throw Refused(
                    "The data didn't match the code that was scanned. Scan the code on the old phone again; " +
                        "if it happens twice, the copy was changed on the way."
                )
            }
            digest.update(plain)
            plainOut.write(plain)
            remaining -= plainSize
        }
        if (sealed.read() != -1) throw Refused("The transfer carried more data than it said it would.")
        if (!MessageDigest.isEqual(digest.digest(), header.sha256)) {
            throw Refused("The backup's checksum doesn't match. Nothing was restored.")
        }
        plainOut.flush()
        return header
    }

    fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun decodeHeader(bytes: ByteArray): Header {
        val input = DataInputStream(bytes.inputStream())
        val magic = ByteArray(4).also { input.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw Refused("That isn't an Operations Sandbox transfer.")
        val version = input.readByte()
        if (version != VERSION) throw Refused("This transfer was made by a newer version of the suite.")
        val chunkSize = input.readInt()
        val plainLength = input.readLong()
        val sha = ByteArray(32).also { input.readFully(it) }
        val versionCode = input.readLong()
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also { input.readFully(it) }
        return Header(chunkSize, plainLength, sha, versionCode, prefix)
    }

    private fun cipher(
        mode: Int,
        key: SecretKeySpec,
        header: Header,
        headerBytes: ByteArray,
        index: Long,
        last: Boolean
    ): Cipher {
        if (index > Int.MAX_VALUE) throw Refused("The transfer is too large.")
        val nonce = ByteBuffer.allocate(12).put(header.noncePrefix).putInt(index.toInt()).array()
        val aad = ByteBuffer.allocate(headerBytes.size + 5)
            .put(headerBytes).putInt(index.toInt()).put(if (last) 1 else 0).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(aad)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int = buffer.size): Int {
        var total = 0
        while (total < length) {
            val read = try {
                input.read(buffer, total, length - total)
            } catch (e: EOFException) {
                -1
            }
            if (read < 0) break
            total += read
        }
        return total
    }
}
