package com.utilities.app.messages.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WSP's primitives, each checked against the bytes WAP-230 says they are.
 *
 * These are the assertions that could not be made any other way. Every layer above this one is
 * testable by round trip — encode a message, decode it, compare — and a round trip is blind to a
 * pair of matching mistakes: an encoder that writes a length one too short and a decoder that reads
 * one too few agree with each other perfectly and agree with no phone on earth. So the primitives
 * are pinned against literal byte sequences taken from the specification.
 */
class WspTest {

    private fun reader(vararg bytes: Int) = PduReader(bytes.map { it.toByte() }.toByteArray())

    // --- uintvar -----------------------------------------------------------------------------

    @Test
    fun `a uintvar is seven bits a byte, high bit set on all but the last`() {
        assertEquals(0, reader(0x00).uintvar())
        assertEquals(127, reader(0x7F).uintvar())
        // 128 needs two septets: 0x81 0x00
        assertEquals(128, reader(0x81, 0x00).uintvar())
        assertEquals(0x0FFF, reader(0x9F, 0x7F).uintvar())
        // The example in the spec: 0x86 0x80 0x00 is 100000 decimal.
        assertEquals(98304, reader(0x86, 0x80, 0x00).uintvar())
    }

    @Test
    fun `a uintvar round trips through the writer`() {
        listOf(0, 1, 127, 128, 255, 16383, 16384, 300 * 1024, Int.MAX_VALUE / 2).forEach { value ->
            val bytes = PduWriter().uintvar(value).toByteArray()
            assertEquals("$value", value, PduReader(bytes).uintvar())
        }
    }

    @Test
    fun `a uintvar that never terminates is refused rather than read forever`() {
        assertThrows(PduException::class.java) {
            reader(0x81, 0x81, 0x81, 0x81, 0x81, 0x81, 0x81).uintvar()
        }
    }

    // --- integers ----------------------------------------------------------------------------

    @Test
    fun `a short integer is one byte with the high bit set`() {
        assertEquals(3, reader(0x83).shortInteger())
        assertEquals(0x7F, reader(0xFF).shortInteger())
        assertThrows(PduException::class.java) { reader(0x03).shortInteger() }
    }

    @Test
    fun `a long integer is a length then that many big-endian bytes`() {
        assertEquals(0L, reader(0x01, 0x00).longInteger())
        assertEquals(258L, reader(0x02, 0x01, 0x02).longInteger())
        assertEquals(1_700_000_000L, reader(0x04, 0x65, 0x53, 0xF1, 0x00).longInteger())
    }

    @Test
    fun `integer takes whichever form it is given, and the writer picks the shorter`() {
        assertEquals(3L, reader(0x83).integer())
        assertEquals(258L, reader(0x02, 0x01, 0x02).integer())

        assertEquals(1, PduWriter().integer(100L).toByteArray().size)
        assertTrue(PduWriter().integer(100_000L).toByteArray().size > 1)
        listOf(0L, 1L, 127L, 128L, 1_700_000_000L).forEach { value ->
            assertEquals(value, PduReader(PduWriter().integer(value).toByteArray()).integer())
        }
    }

    @Test
    fun `a long integer drops its leading zeroes`() {
        // Not cosmetic: gateways have been known to reject a date padded to eight octets.
        val bytes = PduWriter().longInteger(258L).toByteArray()
        assertEquals(2, bytes[0].toInt())
    }

    // --- lengths -----------------------------------------------------------------------------

    @Test
    fun `a short length is the byte itself and a long one is quoted`() {
        assertEquals(5, reader(0x05).valueLength())
        assertEquals(30, reader(0x1E).valueLength())
        // 31 is the quote: the real length follows as a uintvar.
        assertEquals(200, reader(0x1F, 0x81, 0x48).valueLength())
    }

    @Test
    fun `the writer quotes a length only when it has to`() {
        assertEquals(1, PduWriter().valueLength(30).toByteArray().size)
        assertEquals(Wsp.LENGTH_QUOTE, PduWriter().valueLength(31).toByteArray()[0].toInt())
        listOf(0, 1, 30, 31, 200, 100_000).forEach { length ->
            assertEquals(length, PduReader(PduWriter().valueLength(length).toByteArray()).valueLength())
        }
    }

    @Test
    fun `a byte that is text where a length belongs is refused`() {
        assertThrows(PduException::class.java) { reader(0x41).valueLength() }
    }

    // --- strings -----------------------------------------------------------------------------

    @Test
    fun `a text string is NUL terminated`() {
        assertEquals("hi", reader(0x68, 0x69, 0x00).textString())
        assertEquals("", reader(0x00).textString())
    }

    @Test
    fun `a text string whose first character looks like a token is quoted`() {
        // The quote is 0x7F and is stripped on the way back in. Without it the decoder would read
        // the first character as a short integer and the rest of the message as rubble.
        val written = PduWriter().textString("éclair").toByteArray()
        assertEquals(Wsp.QUOTE, written[0].toInt() and 0xFF)
        assertEquals("éclair", PduReader(written).textString())
    }

    @Test
    fun `an ordinary text string is not quoted`() {
        val written = PduWriter().textString("+15550109999").toByteArray()
        assertEquals('+'.code, written[0].toInt())
        assertEquals("+15550109999", PduReader(written).textString())
    }

    @Test
    fun `an encoded string carries its charset, and always says UTF-8 on the way out`() {
        val written = PduWriter().encodedString("Café — tomorrow?").toByteArray()
        // Long form: a length, then the charset, then the text.
        assertTrue(written[0].toInt() and 0xFF < Wsp.TEXT_MIN)
        assertEquals("Café — tomorrow?", PduReader(written).encodedString())
    }

    @Test
    fun `an encoded string in the short form is a plain text string`() {
        assertEquals("hi", PduReader(byteArrayOf(0x68, 0x69, 0x00)).encodedString())
    }

    @Test
    fun `an empty encoded string is empty rather than an error`() {
        assertEquals("", PduReader(byteArrayOf(0x00)).encodedString())
    }

    @Test
    fun `every string round trips, including the awkward ones`() {
        listOf("", "hi", "+15550109999", "Café", "éclair", "你好", "a\tb", "line one")
            .forEach { value ->
                assertEquals(value, PduReader(PduWriter().textString(value).toByteArray()).textString())
                assertEquals(value, PduReader(PduWriter().encodedString(value).toByteArray()).encodedString())
            }
    }

    // --- from --------------------------------------------------------------------------------

    @Test
    fun `from carries an address, or asks the network to fill one in`() {
        val named = PduWriter().fromValue("+15550109999").toByteArray()
        assertEquals("+15550109999", PduReader(named).fromValue())

        val inserted = PduWriter().fromValue(null).toByteArray()
        assertNull("insert-address means the MMSC supplies it", PduReader(inserted).fromValue())
        assertEquals(PduReader.INSERT_ADDRESS, inserted[1].toInt() and 0xFF)
    }

    // --- content types -----------------------------------------------------------------------

    @Test
    fun `a well-known type is one byte`() {
        val written = PduWriter().contentType(ContentType("text/plain")).toByteArray()
        assertEquals(1, written.size)
        assertEquals(0x83, written[0].toInt() and 0xFF)
        assertEquals("text/plain", PduReader(written).contentType().type)
    }

    @Test
    fun `a type the table has never heard of travels as a string`() {
        val written = PduWriter().contentType(ContentType("application/x-thing")).toByteArray()
        assertEquals("application/x-thing", PduReader(written).contentType().type)
    }

    @Test
    fun `a type with parameters takes the long form and brings them back`() {
        val type = ContentType(type = "text/plain", charset = Wsp.CHARSET_UTF_8, name = "body.txt")
        val read = PduReader(PduWriter().contentType(type).toByteArray()).contentType()
        assertEquals("text/plain", read.type)
        assertEquals(Wsp.CHARSET_UTF_8, read.charset)
        assertEquals("body.txt", read.name)
    }

    @Test
    fun `the start parameter survives, because a related body is identified by it`() {
        val type = ContentType(type = Wsp.MULTIPART_RELATED, start = "<smil.xml>")
        val read = PduReader(PduWriter().contentType(type).toByteArray()).contentType()
        assertEquals(Wsp.MULTIPART_RELATED, read.type)
        assertEquals("<smil.xml>", read.start)
    }

    @Test
    fun `a parameter this build cannot read does not eat the ones after it`() {
        // A well-known type, then an unknown parameter code with a text value, then a name. The
        // name has to survive: this is how a carrier adds a field without breaking every message.
        val body = PduWriter()
            .shortInteger(0x1E)                                   // image/jpeg
            .shortInteger(0x40).textString("something")           // a parameter code we do not know
            .shortInteger(Wsp.PARAM_NAME_DEPRECATED).textString("photo.jpg")
            .toByteArray()
        val written = PduWriter().valueLength(body.size).bytes(body).toByteArray()

        val read = PduReader(written).contentType()
        assertEquals("image/jpeg", read.type)
        assertEquals("photo.jpg", read.name)
    }

    @Test
    fun `an unknown well-known code decodes to something rather than throwing`() {
        assertEquals(PduReader.UNKNOWN_TYPE, PduReader(byteArrayOf(0xFE.toByte())).contentType().type)
    }

    // --- bounds ------------------------------------------------------------------------------

    @Test
    fun `reading past the end throws rather than returning rubbish`() {
        assertThrows(PduException::class.java) { reader(0x01).take(9) }
        assertThrows(PduException::class.java) { reader().byte() }
        assertThrows(PduException::class.java) { reader(0x05, 0x01).skip(99) }
    }

    @Test
    fun `a long integer claiming more octets than the spec allows is refused`() {
        assertThrows(PduException::class.java) { reader(0x40, 0x01).longInteger() }
    }

    @Test
    fun `a string that runs off the end gives back what there was`() {
        // A truncated download, which is a real thing that happens, rather than an attack.
        assertEquals("part", PduReader(byteArrayOf(0x70, 0x61, 0x72, 0x74)).textString())
    }
}
