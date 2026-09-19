package com.operations.vaultkit

/**
 * A CBOR reader that exists only so the encoder is checked against something other than itself.
 *
 * It lives in the test source set deliberately: nothing in this app *reads* CBOR — an authenticator
 * only ever produces it — so a decoder in the main source set would be code with no callers and one
 * more place to be wrong. Here it has exactly one job, which is to turn [Cbor]'s output back into
 * values a test can assert on, so that a test asserting "the attestation object contains this
 * authData" is really parsing the structure rather than comparing a blob to a blob the same code
 * produced.
 */
class CborReader(private val bytes: ByteArray) {

    private var pos = 0

    /** Longs, ByteArrays, Strings, Lists and LinkedHashMaps — enough for what this app writes. */
    fun read(): Any? {
        val initial = bytes[pos++].toInt() and 0xFF
        val major = initial shr 5
        val minor = initial and 0x1F
        val value = readLength(minor)

        return when (major) {
            0 -> value
            1 -> -1 - value
            2 -> ByteArray(value.toInt()).also {
                System.arraycopy(bytes, pos, it, 0, it.size)
                pos += it.size
            }
            3 -> String(bytes, pos, value.toInt(), Charsets.UTF_8).also { pos += value.toInt() }
            4 -> (0 until value).map { read() }
            5 -> LinkedHashMap<Any?, Any?>().also { map ->
                repeat(value.toInt()) { map[read()] = read() }
            }
            else -> throw IllegalArgumentException("unsupported major type $major")
        }
    }

    private fun readLength(minor: Int): Long = when {
        minor < 24 -> minor.toLong()
        minor == 24 -> (bytes[pos++].toLong() and 0xFF)
        minor == 25 -> readBigEndian(2)
        minor == 26 -> readBigEndian(4)
        minor == 27 -> readBigEndian(8)
        else -> throw IllegalArgumentException("unsupported length encoding $minor")
    }

    private fun readBigEndian(count: Int): Long {
        var result = 0L
        repeat(count) { result = (result shl 8) or (bytes[pos++].toLong() and 0xFF) }
        return result
    }

    companion object {
        fun parse(bytes: ByteArray): Any? = CborReader(bytes).read()
    }
}
