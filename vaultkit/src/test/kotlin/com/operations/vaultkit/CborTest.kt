package com.operations.vaultkit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The encoder against the spec's own examples, and against an independent reader.
 *
 * The literal byte assertions are from RFC 8949's appendix. They matter more than they look: every
 * one of them is a case where a plausible implementation produces something a decoder still accepts
 * but a relying party's strict parser does not.
 */
class CborTest {

    @Test
    fun `RFC 8949 example encodings`() {
        assertArrayEquals(byteArrayOf(0x00), Cbor.uint(0))
        assertArrayEquals(byteArrayOf(0x0a), Cbor.uint(10))
        assertArrayEquals(byteArrayOf(0x17), Cbor.uint(23))
        assertArrayEquals(byteArrayOf(0x18, 0x18), Cbor.uint(24))
        assertArrayEquals(byteArrayOf(0x19, 0x03, 0xe8.toByte()), Cbor.uint(1000))
        assertArrayEquals(byteArrayOf(0x1a, 0x00, 0x0f, 0x42, 0x40), Cbor.uint(1_000_000))
    }

    @Test
    fun `a negative integer is one byte, which is why ES256 costs nothing to name`() {
        assertArrayEquals(byteArrayOf(0x20), Cbor.int(-1))
        assertArrayEquals(byteArrayOf(0x26), Cbor.int(-7))
        assertArrayEquals(byteArrayOf(0x29), Cbor.int(-10))
        assertArrayEquals(byteArrayOf(0x38, 0x63), Cbor.int(-100))
    }

    @Test
    fun `the shortest form is always used`() {
        // A longer form is legal CBOR and every decoder accepts it; producing it anyway is the
        // difference between canonical output and output that merely parses.
        assertEquals(1, Cbor.uint(23).size)
        assertEquals(2, Cbor.uint(24).size)
        assertEquals(3, Cbor.uint(256).size)
        assertEquals(5, Cbor.uint(65536).size)
    }

    @Test
    fun `text and bytes carry their own length`() {
        assertArrayEquals(byteArrayOf(0x60), Cbor.text(""))
        assertArrayEquals(byteArrayOf(0x61, 0x61), Cbor.text("a"))
        assertArrayEquals(byteArrayOf(0x40), Cbor.bytes(ByteArray(0)))
        assertArrayEquals(byteArrayOf(0x44, 1, 2, 3, 4), Cbor.bytes(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `a map keeps the order it was given`() {
        // Which is the whole reason this encoder takes pre-encoded pairs: a COSE key is specified
        // with its labels in an order, and a map that sorted them would be unable to say so.
        val encoded = Cbor.map(
            listOf(
                Cbor.int(1) to Cbor.int(2),
                Cbor.int(-1) to Cbor.text("later"),
                Cbor.int(3) to Cbor.int(-7)
            )
        )

        @Suppress("UNCHECKED_CAST")
        val parsed = CborReader.parse(encoded) as LinkedHashMap<Any?, Any?>

        assertEquals(listOf(1L, -1L, 3L), parsed.keys.toList())
        assertEquals(-7L, parsed[3L])
    }

    @Test
    fun `nested structures survive a round trip through an independent reader`() {
        val encoded = Cbor.map(
            listOf(
                Cbor.text("fmt") to Cbor.text("none"),
                Cbor.text("attStmt") to Cbor.map(emptyList()),
                Cbor.text("authData") to Cbor.bytes(ByteArray(37) { it.toByte() }),
                Cbor.text("list") to Cbor.array(listOf(Cbor.uint(1), Cbor.text("two")))
            )
        )

        @Suppress("UNCHECKED_CAST")
        val parsed = CborReader.parse(encoded) as Map<Any?, Any?>

        assertEquals("none", parsed["fmt"])
        assertEquals(emptyMap<Any?, Any?>(), parsed["attStmt"])
        assertArrayEquals(ByteArray(37) { it.toByte() }, parsed["authData"] as ByteArray)
        assertEquals(listOf(1L, "two"), parsed["list"])
    }
}
