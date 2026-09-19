package com.operations.vaultkit

import java.io.ByteArrayOutputStream

/**
 * Just enough CBOR to build a WebAuthn attestation object, written out rather than depended on.
 *
 * ## Why this is here instead of a library
 *
 * A passkey's attestation object is CBOR (RFC 8949) and so is the COSE key inside it, so something
 * has to encode it. The alternatives were a CBOR library — a dependency this module has no other
 * use for, in a module whose whole discipline is that the format outlives the build — or the eighty
 * lines below. CBOR's encoding is a three-bit major type and a length, and the two structures this
 * app produces use six of its types. Eighty lines it is, with a reader in the test source set that
 * parses them back independently so the encoder is checked against something other than itself.
 *
 * ## Pre-encoded values
 *
 * [array] and [map] take bytes rather than a value model, which looks lazy and is deliberate: it
 * keeps this file free of a type hierarchy that would exist to serve two callers, and it puts the
 * ordering of a map's entries in the hands of the caller — which matters, because a COSE key is
 * specified with its labels in a particular order and a map this file sorted for you would be a map
 * you could not write to spec.
 */
object Cbor {

    private const val MAJOR_UINT = 0
    private const val MAJOR_NEGATIVE = 1
    private const val MAJOR_BYTES = 2
    private const val MAJOR_TEXT = 3
    private const val MAJOR_ARRAY = 4
    private const val MAJOR_MAP = 5

    /** A non-negative integer. */
    fun uint(value: Long): ByteArray = header(MAJOR_UINT, value)

    /**
     * Any integer. Negatives are major type 1 over `-1 - n`, which is how CBOR stores them and why
     * the COSE algorithm identifier for ES256 (`-7`) encodes as one byte rather than as a sign and
     * a number.
     */
    fun int(value: Long): ByteArray =
        if (value >= 0) header(MAJOR_UINT, value) else header(MAJOR_NEGATIVE, -1 - value)

    fun bytes(value: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(header(MAJOR_BYTES, value.size.toLong()))
            write(value)
        }.toByteArray()

    fun text(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            write(header(MAJOR_TEXT, utf8.size.toLong()))
            write(utf8)
        }.toByteArray()
    }

    /** An array of already-encoded items. */
    fun array(items: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().apply {
            write(header(MAJOR_ARRAY, items.size.toLong()))
            items.forEach(::write)
        }.toByteArray()

    /** A map of already-encoded key/value pairs, kept in the order given. */
    fun map(entries: List<Pair<ByteArray, ByteArray>>): ByteArray =
        ByteArrayOutputStream().apply {
            write(header(MAJOR_MAP, entries.size.toLong()))
            entries.forEach { (key, value) ->
                write(key)
                write(value)
            }
        }.toByteArray()

    /**
     * The major type and the length, in the shortest form that holds it.
     *
     * CBOR allows a longer form than necessary and a decoder must accept it; producing the shortest
     * one is what "canonical" means, and it is the only form a relying party's checks will never
     * find surprising.
     */
    private fun header(major: Int, value: Long): ByteArray {
        val prefix = (major shl 5)
        return when {
            value < 24 -> byteArrayOf((prefix or value.toInt()).toByte())
            value < 0x100 -> byteArrayOf((prefix or 24).toByte(), value.toByte())
            value < 0x10000 -> byteArrayOf(
                (prefix or 25).toByte(),
                (value ushr 8).toByte(),
                value.toByte()
            )
            value < 0x100000000L -> byteArrayOf(
                (prefix or 26).toByte(),
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte()
            )
            else -> ByteArrayOutputStream().apply {
                write(prefix or 27)
                for (shift in 56 downTo 0 step 8) write(((value ushr shift) and 0xFF).toInt())
            }.toByteArray()
        }
    }
}
