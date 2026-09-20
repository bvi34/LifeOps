package com.utilities.app.messages.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The messages themselves: the announcement that arrives over the radio, and the picture message
 * that comes back when it is fetched.
 *
 * Two kinds of test here and they check different things.
 *
 * **Hand-built PDUs** — bytes assembled field by field the way a network assembles them — are the
 * only honest test of the *decoder*, because nothing in this app writes an `m-notification-ind`. If
 * the decoder were tested against an encoder of our own, both could be wrong in the same direction
 * and agree.
 *
 * **Round trips** are the right test of the *encoder*: build a message, decode it back, and compare
 * the parts. A byte-for-byte comparison against a hand-written expectation would only prove the
 * encoder still does what it did yesterday. The primitives underneath are separately pinned against
 * the specification in `WspTest`, which is what stops a matching pair of mistakes passing.
 */
class PduCodecTest {

    // -----------------------------------------------------------------------------------------
    // What arrives over the radio
    // -----------------------------------------------------------------------------------------

    /** An `m-notification-ind` as a network sends one, assembled field by field. */
    private fun notificationBytes(
        transactionId: String = "T-9174",
        location: String? = "http://mmsc.example.net/fetch?id=9174",
        from: String? = "+15550109999/TYPE=PLMN",
        subject: String? = "Holiday",
        size: Long = 184_320,
        messageClass: Int = MmsHeaders.MESSAGE_CLASS_PERSONAL
    ): ByteArray = PduWriter().apply {
        byte(MmsHeaders.MESSAGE_TYPE); byte(MmsHeaders.TYPE_NOTIFICATION_IND)
        byte(MmsHeaders.TRANSACTION_ID); textString(transactionId)
        byte(MmsHeaders.MMS_VERSION); byte(MmsHeaders.VERSION_1_2)
        from?.let { byte(MmsHeaders.FROM); fromValue(it) }
        subject?.let { byte(MmsHeaders.SUBJECT); encodedString(it) }
        byte(MmsHeaders.MESSAGE_CLASS); byte(messageClass)
        byte(MmsHeaders.MESSAGE_SIZE); longInteger(size)
        // Expiry, in the relative form — which is what most networks send.
        byte(MmsHeaders.EXPIRY)
        val expiry = block { byte(MmsHeaders.TIME_RELATIVE); longInteger(259_200) }
        valueLength(expiry.size); bytes(expiry)
        location?.let { byte(MmsHeaders.CONTENT_LOCATION); textString(it) }
    }.toByteArray()

    @Test
    fun `a notification says where the message is and how big it will be`() {
        val read = PduDecoder.notification(notificationBytes())
        assertNotNull(read)
        assertEquals("T-9174", read!!.transactionId)
        assertEquals("http://mmsc.example.net/fetch?id=9174", read.contentLocation)
        assertEquals("+15550109999/TYPE=PLMN", read.from)
        assertEquals("Holiday", read.subject)
        assertEquals(184_320L, read.messageSize)
        assertTrue(read.fetchable)
        assertFalse(read.isAdvertisement)
    }

    @Test
    fun `a notification with nowhere to fetch from says so instead of pretending`() {
        val read = PduDecoder.notification(notificationBytes(location = null))
        assertNotNull(read)
        assertFalse(read!!.fetchable)
        assertNull(read.contentLocation)
    }

    @Test
    fun `an advertisement is distinguishable, because auto-downloading one costs money`() {
        val read = PduDecoder.notification(
            notificationBytes(messageClass = MmsHeaders.MESSAGE_CLASS_ADVERTISEMENT)
        )
        assertTrue(read!!.isAdvertisement)
    }

    @Test
    fun `an expiry in the relative form resolves against the clock`() {
        val read = PduDecoder.notification(notificationBytes())!!
        val threeDays = 3 * 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        assertTrue("expiry was ${read.expiry}", read.expiry in (now + threeDays - 60_000)..(now + threeDays + 60_000))
    }

    @Test
    fun `a delivery report coming down the same pipe is not a notification`() {
        // Read receipts and delivery reports arrive as WAP pushes too. The right thing to do with
        // one is nothing, and the decoder says so by refusing to call it a notification.
        val delivery = PduWriter()
            .byte(MmsHeaders.MESSAGE_TYPE).byte(MmsHeaders.TYPE_DELIVERY_IND)
            .byte(MmsHeaders.MMS_VERSION).byte(MmsHeaders.VERSION_1_2)
            .byte(MmsHeaders.MESSAGE_ID).textString("m-1")
            .toByteArray()
        assertNull(PduDecoder.notification(delivery))
        assertEquals(MmsHeaders.TYPE_DELIVERY_IND, PduDecoder.messageType(delivery))
    }

    @Test
    fun `rubbish decodes to null rather than throwing`() {
        // The input arrived over a network from a stranger and is parsed in a broadcast receiver.
        listOf(
            ByteArray(0),
            byteArrayOf(0x00),
            ByteArray(64) { 0xFF.toByte() },
            ByteArray(200) { (it * 7).toByte() }
        ).forEach {
            assertNull(PduDecoder.notification(it))
            assertNull(PduDecoder.message(it)?.parts?.takeIf { parts -> parts.size > 100 })
        }
    }

    @Test
    fun `a notification truncated half way through is not a notification`() {
        val whole = notificationBytes()
        val half = whole.copyOfRange(0, whole.size / 2)
        val read = PduDecoder.notification(half)
        // Either it fails outright or it comes back with nothing to fetch. Both are honest; what
        // must not happen is a content location assembled out of the bytes that followed.
        assertTrue(read == null || !read.fetchable)
    }

    @Test
    fun `an unknown header does not make the ones after it unreadable`() {
        // The property that lets a carrier add a field without breaking every message it sends.
        val bytes = PduWriter().apply {
            byte(MmsHeaders.MESSAGE_TYPE); byte(MmsHeaders.TYPE_NOTIFICATION_IND)
            byte(MmsHeaders.TRANSACTION_ID); textString("T-1")
            byte(MmsHeaders.MMS_VERSION); byte(MmsHeaders.VERSION_1_2)
            byte(0xB4); textString("something this build has never heard of")
            byte(0xB5); byte(0x81)
            byte(MmsHeaders.CONTENT_LOCATION); textString("http://mmsc.example.net/x")
        }.toByteArray()

        val read = PduDecoder.notification(bytes)
        assertEquals("http://mmsc.example.net/x", read?.contentLocation)
    }

    // -----------------------------------------------------------------------------------------
    // The message itself
    // -----------------------------------------------------------------------------------------

    private val photo = MmsPart(
        contentType = "image/jpeg",
        data = ByteArray(512) { (it * 31 and 0xFF).toByte() },
        name = "beach.jpg",
        contentId = "beach.jpg",
        contentLocation = "beach.jpg"
    )

    private val caption = MmsPart(
        contentType = "text/plain",
        data = "Wish you were here — café on the corner".toByteArray(Charsets.UTF_8),
        name = "text_0.txt",
        contentId = "text_0.txt",
        contentLocation = "text_0.txt",
        charset = Wsp.CHARSET_UTF_8
    )

    @Test
    fun `a message survives being encoded and read back`() {
        val parts = listOf(caption, photo)
        val encoded = PduEncoder.sendReq(
            to = listOf("+15550109999"),
            parts = parts + PduEncoder.smil(parts),
            subject = "Holiday",
            transactionId = "T-42"
        )

        val read = PduDecoder.message(encoded)
        assertNotNull(read)
        assertEquals(MmsHeaders.TYPE_SEND_REQ, read!!.type)
        assertEquals(listOf("+15550109999"), read.to)
        assertEquals("Holiday", read.subject)
        assertEquals("T-42", read.transactionId)

        assertEquals("Wish you were here — café on the corner", read.text())

        val attachments = read.attachments()
        assertEquals(1, attachments.size)
        assertEquals("image/jpeg", attachments.single().contentType)
        assertTrue("the bytes came back", attachments.single().data.contentEquals(photo.data))
        assertEquals("beach.jpg", attachments.single().contentLocation)
    }

    @Test
    fun `several recipients survive, which is what a group message is`() {
        val to = listOf("+15550109999", "+15550100001", "5550100002")
        val read = PduDecoder.message(
            PduEncoder.sendReq(to = to, parts = listOf(caption))
        )
        assertEquals(to, read!!.to)
    }

    @Test
    fun `a message with no recipients is refused rather than sent nowhere`() {
        val thrown = runCatching { PduEncoder.sendReq(to = emptyList(), parts = listOf(caption)) }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `the date goes out in seconds and comes back in millis`() {
        // The wire carries seconds. Everything above this package speaks millis, and the conversion
        // is done once, here, rather than in four callers with three of them forgetting.
        val sent = 1_700_000_000_000L
        val read = PduDecoder.message(
            PduEncoder.sendReq(to = listOf("+15550109999"), parts = listOf(caption), date = sent)
        )
        assertEquals(sent, read!!.date)
    }

    @Test
    fun `a body with a layout part is related, and one without is mixed`() {
        val withSmil = listOf(caption, photo).let { it + PduEncoder.smil(it) }
        assertEquals(Wsp.MULTIPART_RELATED, PduEncoder.bodyContentType(withSmil).type)
        assertEquals("<${PduEncoder.SMIL_NAME}>", PduEncoder.bodyContentType(withSmil).start)

        assertEquals(Wsp.MULTIPART_MIXED, PduEncoder.bodyContentType(listOf(caption)).type)
        assertNull(PduEncoder.bodyContentType(listOf(caption)).start)
    }

    @Test
    fun `the body type survives the round trip`() {
        val parts = listOf(caption, photo)
        val read = PduDecoder.message(
            PduEncoder.sendReq(to = listOf("+15550109999"), parts = parts + PduEncoder.smil(parts))
        )
        assertEquals(Wsp.MULTIPART_RELATED, read!!.bodyType)
    }

    @Test
    fun `the layout part names every attachment it lays out`() {
        val parts = listOf(caption, photo)
        val smil = PduEncoder.smil(parts)
        val document = String(smil.data, Charsets.UTF_8)
        assertTrue(smil.isSmil)
        assertTrue("the picture is placed", document.contains("beach.jpg"))
        assertTrue("the caption is placed", document.contains("text_0.txt"))
        assertTrue(document.startsWith("<smil>"))
    }

    @Test
    fun `the layout part is not shown as an attachment or as text`() {
        val parts = listOf(caption, photo)
        val read = PduDecoder.message(
            PduEncoder.sendReq(to = listOf("+15550109999"), parts = parts + PduEncoder.smil(parts))
        )!!
        assertTrue(read.attachments().none { it.isSmil })
        assertFalse(read.text().contains("smil"))
    }

    @Test
    fun `a text-only message is still a valid message`() {
        // Sent when a thread has several recipients: a group text is an MMS with nothing in it but
        // words, and it has to encode as cleanly as one with a photograph.
        val read = PduDecoder.message(
            PduEncoder.sendReq(to = listOf("+15550109999", "+15550100001"), parts = listOf(caption))
        )!!
        assertEquals("Wish you were here — café on the corner", read.text())
        assertTrue(read.attachments().isEmpty())
    }

    @Test
    fun `several pictures all come back, in order, with their bytes intact`() {
        val photos = (1..3).map { index ->
            MmsPart(
                contentType = "image/png",
                data = ByteArray(300 + index) { (it * index and 0xFF).toByte() },
                name = "shot$index.png",
                contentId = "shot$index.png",
                contentLocation = "shot$index.png"
            )
        }
        val read = PduDecoder.message(
            PduEncoder.sendReq(to = listOf("+15550109999"), parts = photos + PduEncoder.smil(photos))
        )!!
        val back = read.attachments()
        assertEquals(3, back.size)
        photos.forEachIndexed { index, original ->
            assertEquals(original.contentType, back[index].contentType)
            assertTrue("picture $index", original.data.contentEquals(back[index].data))
            assertEquals(original.contentLocation, back[index].contentLocation)
        }
    }

    @Test
    fun `a part claiming more bytes than the message holds is refused, not clamped`() {
        // Clamping would turn a corrupt message into a plausible one, assembled out of whatever
        // followed it. Refusing is the only safe answer.
        val body = PduWriter()
            .uintvar(1)
            .uintvar(2)       // header length
            .uintvar(9_999)   // data length — a lie
            .shortInteger(0x03)
            .byte(0x00)
            .bytes(ByteArray(4))
            .toByteArray()
        val bytes = PduWriter()
            .byte(MmsHeaders.MESSAGE_TYPE).byte(MmsHeaders.TYPE_RETRIEVE_CONF)
            .byte(MmsHeaders.MMS_VERSION).byte(MmsHeaders.VERSION_1_2)
            .byte(MmsHeaders.CONTENT_TYPE).contentType(ContentType(Wsp.MULTIPART_MIXED))
            .bytes(body)
            .toByteArray()

        assertNull(PduDecoder.message(bytes))
    }

    @Test
    fun `a part count larger than the message could possibly hold is refused`() {
        val bytes = PduWriter()
            .byte(MmsHeaders.MESSAGE_TYPE).byte(MmsHeaders.TYPE_RETRIEVE_CONF)
            .byte(MmsHeaders.MMS_VERSION).byte(MmsHeaders.VERSION_1_2)
            .byte(MmsHeaders.CONTENT_TYPE).contentType(ContentType(Wsp.MULTIPART_MIXED))
            .uintvar(500_000)
            .toByteArray()
        assertNull(PduDecoder.message(bytes))
    }

    @Test
    fun `a part header this build cannot read does not lose the part`() {
        val part = MmsPart(
            contentType = "image/jpeg",
            data = ByteArray(16) { it.toByte() },
            name = "x.jpg",
            contentId = "x.jpg",
            contentLocation = "x.jpg"
        )
        val headers = PduWriter()
            .contentType(ContentType("image/jpeg", name = "x.jpg"))
            .shortInteger(0x2B).textString("via something")   // an unrelated WSP header
            .shortInteger(Wsp.HEADER_CONTENT_LOCATION).textString("x.jpg")
            .toByteArray()
        val body = PduWriter()
            .uintvar(1)
            .uintvar(headers.size)
            .uintvar(part.data.size)
            .bytes(headers)
            .bytes(part.data)
            .toByteArray()
        val bytes = PduWriter()
            .byte(MmsHeaders.MESSAGE_TYPE).byte(MmsHeaders.TYPE_RETRIEVE_CONF)
            .byte(MmsHeaders.MMS_VERSION).byte(MmsHeaders.VERSION_1_2)
            .byte(MmsHeaders.CONTENT_TYPE).contentType(ContentType(Wsp.MULTIPART_MIXED))
            .bytes(body)
            .toByteArray()

        val read = PduDecoder.message(bytes)!!
        assertEquals(1, read.parts.size)
        assertEquals("x.jpg", read.parts.single().contentLocation)
        assertTrue(part.data.contentEquals(read.parts.single().data))
    }

    // -----------------------------------------------------------------------------------------
    // The acknowledgements
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the two acknowledgements say what they are and never allow a report`() {
        // A network that never hears these keeps re-pushing the notification, which the household
        // experiences as the same picture arriving four times.
        val notify = PduDecoder.messageType(PduEncoder.notifyRespInd("T-1"))
        assertEquals(MmsHeaders.TYPE_NOTIFYRESP_IND, notify)

        val ack = PduDecoder.messageType(PduEncoder.acknowledgeInd("T-1"))
        assertEquals(MmsHeaders.TYPE_ACKNOWLEDGE_IND, ack)

        // Report-allowed is No in both. A read receipt is something a household opts into, and
        // nothing in this app turns it on.
        listOf(PduEncoder.notifyRespInd("T-1"), PduEncoder.acknowledgeInd("T-1")).forEach { pdu ->
            val index = pdu.indexOfFirst { it.toInt() and 0xFF == MmsHeaders.REPORT_ALLOWED }
            assertTrue("report-allowed is present", index >= 0)
            assertEquals(MmsHeaders.NO, pdu[index + 1].toInt() and 0xFF)
        }
    }

    @Test
    fun `a transaction id is unique between messages`() {
        val ids = (1..50).map { PduEncoder.newTransactionId(now = 1_700_000_000_000L) }
        assertEquals(ids.size, ids.toSet().size)
    }
}
