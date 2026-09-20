package com.utilities.app.messages.seal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two phones having a conversation.
 *
 * Everything here is Alice and Bob talking through the real protocol — no fakes, no shortcuts — and
 * the assertions are the properties somebody would actually notice losing: messages arriving out of
 * order still open, a replay does not, an old key does not open a new message, and a stolen state
 * stops working once the conversation moves on.
 *
 * The primitives underneath are separately pinned against RFC 7748 and RFC 5869 in `PrimitivesTest`,
 * which is what stops a self-consistently wrong implementation passing all of this.
 */
class SessionTest {

    private val alice = Identity.generate()
    private val bob = Identity.generate()

    /** Alice has Bob's bundle and opens a conversation. */
    private fun opening(text: String): Pair<Session, ByteArray> {
        val session = Session.start(alice, bob.bundle())
        return session to session.seal(text.toByteArray())
    }

    private fun accept(payload: ByteArray): Pair<Session, String> {
        val envelope = Envelope.decode(payload)
        val session = Session.accept(bob, envelope)
        return session to String(session.open(envelope))
    }

    private fun Session.send(text: String): ByteArray = seal(text.toByteArray())

    private fun Session.receive(payload: ByteArray): String = String(open(Envelope.decode(payload)))

    // -----------------------------------------------------------------------------------------
    // The ordinary case
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a first message opens a conversation with somebody who has never heard of us`() {
        val (_, payload) = opening("are you free on Thursday?")
        val (_, read) = accept(payload)
        assertEquals("are you free on Thursday?", read)
    }

    @Test
    fun `a conversation runs in both directions for as long as anybody wants`() {
        val (aliceSession, first) = opening("one")
        val (bobSession, firstRead) = accept(first)
        assertEquals("one", firstRead)

        assertEquals("two", aliceSession.receive(bobSession.send("two")))
        assertEquals("three", bobSession.receive(aliceSession.send("three")))

        repeat(30) { index ->
            assertEquals("a$index", bobSession.receive(aliceSession.send("a$index")))
            assertEquals("b$index", aliceSession.receive(bobSession.send("b$index")))
        }
    }

    @Test
    fun `several messages in a row, before any reply, all open`() {
        // The common case on a phone: somebody sends three in a burst and the other end reads them
        // together. Every one is a step of the sending chain with no ratchet turn between them.
        val aliceSession = Session.start(alice, bob.bundle())
        val burst = (1..5).map { aliceSession.send("message $it") }

        val bobSession = Session.accept(bob, Envelope.decode(burst.first()))
        burst.forEachIndexed { index, payload ->
            assertEquals("message ${index + 1}", bobSession.receive(payload))
        }
    }

    @Test
    fun `only the first message carries the opening keys`() {
        val aliceSession = Session.start(alice, bob.bundle())
        val first = Envelope.decode(aliceSession.send("one"))
        assertTrue(first.opening)
        assertArrayEquals(alice.identityKey.publicKey, first.identityKey)

        // Still opening, because Bob has not answered: he may not have received the first one.
        assertTrue(Envelope.decode(aliceSession.send("two")).opening)

        val bobSession = Session.accept(bob, first)
        bobSession.open(first)
        aliceSession.receive(bobSession.send("got it"))

        assertFalse("once they have replied, the keys stop travelling", Envelope.decode(aliceSession.send("three")).opening)
    }

    // -----------------------------------------------------------------------------------------
    // The network being a network
    // -----------------------------------------------------------------------------------------

    @Test
    fun `messages that arrive out of order still open`() {
        // SMS segments are reordered by the network routinely. A ratchet that refused anything not
        // next would make this app lose messages that every other messaging app shows.
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("one")
        val second = aliceSession.send("two")
        val third = aliceSession.send("three")

        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))
        assertEquals("three", bobSession.receive(third))
        assertEquals("two", bobSession.receive(second))
    }

    @Test
    fun `a message delayed across a reply still opens`() {
        // The hard case: a message from the old chain turns up after the ratchet has turned. Its key
        // has to have been kept, which is what previousChainLength in the header is for.
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("first")
        val strayed = aliceSession.send("strayed")

        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))

        val reply = bobSession.send("replying before yours arrived")
        assertEquals("replying before yours arrived", aliceSession.receive(reply))
        aliceSession.send("and on we go").let { bobSession.receive(it) }

        assertEquals("strayed", bobSession.receive(strayed))
    }

    @Test
    fun `a message delivered twice only opens once`() {
        // Carriers duplicate deliveries. Opening a replay twice would show the household the same
        // message twice, and would mean a message key surviving its use.
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("one")
        val second = aliceSession.send("two")

        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))
        assertEquals("two", bobSession.receive(second))
        assertThrows(SealException::class.java) { bobSession.receive(second) }
    }

    @Test
    fun `a message claiming to be billions ahead is refused rather than ground out`() {
        // Without the bound, one text costs the receiving phone two billion HMACs.
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("one")
        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))

        val real = Envelope.decode(aliceSession.send("two"))
        val absurd = Envelope.encodeOrdinary(
            Ratchet.Outgoing(
                header = Ratchet.Header(
                    ratchetKey = real.header.ratchetKey,
                    previousChainLength = real.header.previousChainLength,
                    number = 2_000_000_000
                ),
                nonce = real.nonce,
                ciphertext = real.ciphertext
            )
        )
        assertThrows(SealException::class.java) { bobSession.receive(absurd) }
    }

    // -----------------------------------------------------------------------------------------
    // The properties the ratchet exists for
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a state captured later cannot open an earlier message`() {
        // Forward secrecy, which is what matters when a phone is lost: today's state says nothing
        // about what was read last week.
        val aliceSession = Session.start(alice, bob.bundle())
        val early = aliceSession.send("the early one")

        val bobSession = Session.accept(bob, Envelope.decode(early))
        bobSession.open(Envelope.decode(early))
        repeat(5) { index ->
            aliceSession.receive(bobSession.send("b$index"))
            bobSession.receive(aliceSession.send("a$index"))
        }

        val stolen = Session.restore(bob, bobSession.snapshot())
        assertThrows(SealException::class.java) { stolen.receive(early) }
    }

    @Test
    fun `a stolen state stops working once the conversation moves on`() {
        // Post-compromise recovery: the thing that matters when a phone was lost and got back. The
        // thief has a copy of the state; one round trip later it is worthless, because the next
        // agreement needs a private key that was generated after the theft.
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("one")
        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))
        aliceSession.receive(bobSession.send("two"))

        val thief = Session.restore(bob, bobSession.snapshot())

        // Alice and Bob exchange one more round, which turns the ratchet twice.
        bobSession.receive(aliceSession.send("three"))
        aliceSession.receive(bobSession.send("four"))

        val laterMessage = aliceSession.send("five")
        assertEquals("five", bobSession.receive(laterMessage))
        assertThrows(SealException::class.java) { thief.receive(laterMessage) }
    }

    @Test
    fun `each message is encrypted under its own key`() {
        val aliceSession = Session.start(alice, bob.bundle())
        val ciphertexts = (1..10).map { Bytes.hex(Envelope.decode(aliceSession.send("same words")).ciphertext) }
        assertEquals("the same sentence must not encrypt to the same bytes", ciphertexts.size, ciphertexts.toSet().size)
    }

    // -----------------------------------------------------------------------------------------
    // Somebody interfering
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an edited ciphertext does not open`() {
        val aliceSession = Session.start(alice, bob.bundle())
        val payload = aliceSession.send("transfer it to account 1234")
        payload[payload.size - 5] = (payload[payload.size - 5].toInt() xor 0x20).toByte()
        assertThrows(SealException::class.java) { accept(payload) }
    }

    @Test
    fun `an edited header does not open`() {
        // The header is in the associated data, so editing it is as fatal as editing the body — and
        // this is the assertion that proves the binding is actually wired up.
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("one")
        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))

        val second = aliceSession.send("two")
        val parsed = Envelope.decode(second)
        val edited = Envelope.encodeOrdinary(
            Ratchet.Outgoing(
                header = Ratchet.Header(parsed.header.ratchetKey, parsed.header.previousChainLength, 0),
                nonce = parsed.nonce,
                ciphertext = parsed.ciphertext
            )
        )
        assertThrows(SealException::class.java) { bobSession.receive(edited) }
    }

    @Test
    fun `a message meant for one conversation does not open in another`() {
        // What binding both identity keys into the associated data buys.
        val carol = Identity.generate()
        val toBob = Session.start(alice, bob.bundle()).send("meant for Bob")
        assertThrows(SealException::class.java) {
            val envelope = Envelope.decode(toBob)
            Session.accept(carol, envelope).open(envelope)
        }
    }

    @Test
    fun `an ordinary message from a stranger cannot start a conversation`() {
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("one")
        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))
        aliceSession.receive(bobSession.send("two"))

        val ordinary = Envelope.decode(aliceSession.send("three"))
        assertFalse(ordinary.opening)
        assertThrows(SealException::class.java) { Session.accept(Identity.generate(), ordinary) }
    }

    // -----------------------------------------------------------------------------------------
    // Surviving the process
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a conversation continues across a restart of either end`() {
        var aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("one")
        var bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))

        repeat(4) { index ->
            // Both ends are stored and rebuilt between every single message, which is what actually
            // happens: a broadcast receiver handles one text and the process goes away.
            aliceSession = Session.restore(alice, aliceSession.snapshot())
            bobSession = Session.restore(bob, bobSession.snapshot())
            assertEquals("a$index", bobSession.receive(aliceSession.send("a$index")))
            aliceSession = Session.restore(alice, aliceSession.snapshot())
            bobSession = Session.restore(bob, bobSession.snapshot())
            assertEquals("b$index", aliceSession.receive(bobSession.send("b$index")))
        }
    }

    @Test
    fun `a restored session keeps the keys it was holding for messages that had not arrived`() {
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.send("first")
        val strayed = aliceSession.send("strayed")
        val third = aliceSession.send("third")

        var bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))
        assertEquals("third", bobSession.receive(third))

        bobSession = Session.restore(bob, bobSession.snapshot())
        assertEquals("strayed", bobSession.receive(strayed))
    }

    // -----------------------------------------------------------------------------------------
    // Trust
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a new session is encrypted but unverified, and says so`() {
        val session = Session.start(alice, bob.bundle())
        assertEquals(Trust.FIRST_USE, session.trustLevel)
        session.markVerified()
        assertEquals(Trust.VERIFIED, session.trustLevel)
    }

    @Test
    fun `a session is not confirmed until the other end has answered`() {
        val aliceSession = Session.start(alice, bob.bundle())
        assertFalse(aliceSession.confirmed)

        val first = aliceSession.send("one")
        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))
        assertTrue(bobSession.confirmed)

        aliceSession.receive(bobSession.send("two"))
        assertTrue(aliceSession.confirmed)
    }

    @Test
    fun `verification survives being stored`() {
        val session = Session.start(alice, bob.bundle())
        session.markVerified()
        assertEquals(Trust.VERIFIED, Session.restore(alice, session.snapshot()).trustLevel)
    }
}

/** The bundle, the envelope and the text a sealed message travels in. */
class EnvelopeTest {

    private val alice = Identity.generate()
    private val bob = Identity.generate()

    @Test
    fun `a bundle round trips`() {
        val bundle = bob.bundle()
        assertEquals(bundle, PreKeyBundle.decode(bundle.encode()))
        assertEquals(PreKeyBundle.BYTES, bundle.encode().size)
    }

    @Test
    fun `a bundle of the wrong size is refused`() {
        assertThrows(SealException::class.java) { PreKeyBundle.decode(ByteArray(63)) }
        assertThrows(SealException::class.java) { PreKeyBundle(ByteArray(31), ByteArray(32)) }
    }

    @Test
    fun `an envelope round trips, both kinds`() {
        val session = Session.start(alice, bob.bundle())
        val opening = Envelope.decode(session.seal("one".toByteArray()))
        assertTrue(opening.opening)
        assertEquals(Envelope.KIND_OPENING, opening.kind)

        val bobSession = Session.accept(bob, opening)
        bobSession.open(opening)
        session.open(Envelope.decode(bobSession.seal("two".toByteArray())))

        val ordinary = Envelope.decode(session.seal("three".toByteArray()))
        assertFalse(ordinary.opening)
        assertEquals(Envelope.KIND_ORDINARY, ordinary.kind)
    }

    @Test
    fun `a truncated envelope never opens, at any length`() {
        // Decoding is allowed to succeed on a few of these — a zero-length message is legal, so a
        // payload cut back to exactly the tag still parses. What must never happen is one of them
        // *opening*, which is the property asserted.
        val payload = Session.start(alice, bob.bundle()).seal("something".toByteArray())
        for (length in 0 until payload.size) {
            val cut = payload.copyOfRange(0, length)
            val opened = runCatching {
                val envelope = Envelope.decode(cut)
                Session.accept(bob, envelope).open(envelope)
            }
            assertTrue("length $length opened", opened.isFailure)
        }
    }

    @Test
    fun `an envelope from a newer version is refused with a sentence`() {
        val payload = Session.start(alice, bob.bundle()).seal("x".toByteArray())
        payload[0] = 9
        val thrown = assertThrows(SealException::class.java) { Envelope.decode(payload) }
        assertTrue(thrown.message!!.contains("newer version"))
    }

    @Test
    fun `an unknown kind is refused`() {
        val payload = Session.start(alice, bob.bundle()).seal("x".toByteArray())
        payload[1] = 7
        assertThrows(SealException::class.java) { Envelope.decode(payload) }
    }

    @Test
    fun `a sealed message travels inside an ordinary text and comes back out`() {
        val payload = Session.start(alice, bob.bundle()).seal("meet me at six".toByteArray())
        val body = SealedText.wrap(payload)

        assertTrue(SealedText.looksSealed(body))
        assertArrayEquals(payload, SealedText.unwrap(body))
    }

    @Test
    fun `the wrapper tells somebody without the app what they are looking at`() {
        // A line of base64 from a friend, with no explanation, reads as a compromised phone.
        val body = SealedText.wrap(ByteArray(80))
        assertTrue(body.substringBefore('\n').contains("encrypted"))
        assertTrue(body.substringBefore('\n').contains("Utilities"))
    }

    @Test
    fun `an ordinary message is not mistaken for a sealed one`() {
        listOf("", "hello", "see you at 6", "[sealed]", "sealed:1").forEach {
            assertFalse(it, SealedText.looksSealed(it))
            assertEquals(null, SealedText.unwrap(it))
        }
    }

    @Test
    fun `a sealed message survives a gateway adding a footer`() {
        val payload = Session.start(alice, bob.bundle()).seal("hello".toByteArray())
        val mangled = SealedText.wrap(payload) + "\n\nSent from a carrier that adds these"
        assertArrayEquals(payload, SealedText.unwrap(mangled))
    }
}

/** The sixty digits. */
class SafetyNumberTest {

    private val alice = Identity.generate()
    private val bob = Identity.generate()

    @Test
    fun `both ends compute the same number without agreeing who is who`() {
        // The one failure that would make this feature worse than nothing.
        assertEquals(
            SafetyNumber.of(alice.identityKey.publicKey, bob.identityKey.publicKey),
            SafetyNumber.of(bob.identityKey.publicKey, alice.identityKey.publicKey)
        )
    }

    @Test
    fun `it is sixty digits and nothing else`() {
        val number = SafetyNumber.of(alice.identityKey.publicKey, bob.identityKey.publicKey)
        assertEquals(60, number.length)
        assertTrue(number.all { it.isDigit() })
    }

    @Test
    fun `a different key is a different number`() {
        val carol = Identity.generate()
        assertNotEquals(
            SafetyNumber.of(alice.identityKey.publicKey, bob.identityKey.publicKey),
            SafetyNumber.of(alice.identityKey.publicKey, carol.identityKey.publicKey)
        )
    }

    @Test
    fun `the same keys always give the same number`() {
        val once = SafetyNumber.of(alice.identityKey.publicKey, bob.identityKey.publicKey)
        val twice = SafetyNumber.of(alice.identityKey.publicKey, bob.identityKey.publicKey)
        assertEquals(once, twice)
    }

    @Test
    fun `it is shown in readable blocks and compared ignoring them`() {
        val number = SafetyNumber.of(alice.identityKey.publicKey, bob.identityKey.publicKey)
        val shown = SafetyNumber.display(number)
        assertEquals(12, shown.split(" ").size)
        assertTrue(SafetyNumber.matches(number, shown))
        assertTrue(SafetyNumber.matches(number, number))
        assertFalse(SafetyNumber.matches(number, number.dropLast(1) + "0".repeat(1).let { "9" }))
        assertFalse(SafetyNumber.matches(number, ""))
    }

    @Test
    fun `a session's number is the number for its two identities`() {
        val session = Session.start(alice, bob.bundle())
        assertEquals(
            SafetyNumber.of(alice.identityKey.publicKey, bob.identityKey.publicKey),
            session.safetyNumber()
        )
    }
}
