package com.utilities.app.messages.seal

import java.util.Base64

/**
 * The byte handling the rest of this package needs, in one place so it is written once and
 * correctly.
 *
 * The only interesting function here is [constantTimeEquals], and it is interesting for a reason
 * worth stating: `ByteArray.contentEquals` returns the moment two bytes differ, so how long it takes
 * says how many leading bytes matched. Comparing an authentication tag with it hands an attacker a
 * way to find the right tag one byte at a time.
 */
object Bytes {

    /** Compare without the answer depending on where they first differ. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
        return difference == 0
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { part ->
            System.arraycopy(part, 0, out, at, part.size)
            at += part.size
        }
        return out
    }

    /** Big-endian, four bytes. Used for the counters in a ratchet header. */
    fun int(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) throw SealException("not enough bytes for an integer")
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    fun slice(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            throw SealException("a field claims $length bytes at $offset of ${bytes.size}")
        }
        return bytes.copyOfRange(offset, offset + length)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.filterNot { it.isWhitespace() }
        if (clean.length % 2 != 0) throw SealException("an odd number of hex digits")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Base64 without padding, and URL-safe.
     *
     * Not decoration: a sealed message travels inside the text of an SMS, and `+` and `/` survive
     * that intact while `=` padding is dropped by enough gateways to be worth not relying on.
     */
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray = try {
        Base64.getUrlDecoder().decode(text.trim())
    } catch (e: IllegalArgumentException) {
        throw SealException("that is not a sealed payload")
    }

    /** Overwrite a key we are finished with. Best effort — the JVM may already have copied it. */
    fun wipe(bytes: ByteArray) = bytes.fill(0)
}
