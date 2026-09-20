package com.utilities.app.messages.seal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session, written down and read back.
 *
 * This is the file that decides whether a conversation survives the process going away, which on a
 * phone is after almost every message: a broadcast receiver handles one text and Android reclaims
 * the app. A codec that loses one field loses the conversation, and it loses it silently — the next
 * message simply fails to open.
 *
 * So the test is a real conversation, stored mid-flight, with the awkward parts present: skipped
 * keys waiting for messages that have not arrived, and a chain that only exists in one direction.
 */
class SessionCodecTest {

    private val alice = Identity.generate()
    private val bob = Identity.generate()

    @Test
    fun `a brand new session round trips`() {
        val session = Session.start(alice, bob.bundle())
        val restored = Session.restore(alice, SessionCodec.decode(SessionCodec.encode(session.snapshot())))
        assertArrayEquals(session.peerIdentityKey, restored.peerIdentityKey)
        assertEquals(session.trustLevel, restored.trustLevel)
        assertEquals(session.safetyNumber(), restored.safetyNumber())
    }

    @Test
    fun `a session with keys held for messages that have not arrived round trips`() {
        // The case that is easy to lose: the skipped-key store is a map with a compound key, and a
        // codec that drops it looks perfectly fine until a delayed message turns up.
        val aliceSession = Session.start(alice, bob.bundle())
        val first = aliceSession.seal("first".toByteArray())
        val strayed = aliceSession.seal("strayed".toByteArray())
        val third = aliceSession.seal("third".toByteArray())

        val bobSession = Session.accept(bob, Envelope.decode(first))
        bobSession.open(Envelope.decode(first))
        bobSession.open(Envelope.decode(third))

        val restored = Session.restore(bob, SessionCodec.decode(SessionCodec.encode(bobSession.snapshot())))
        assertEquals("strayed", String(restored.open(Envelope.decode(strayed))))
    }

    @Test
    fun `verification survives, because it is the one thing somebody did by hand`() {
        val session = Session.start(alice, bob.bundle())
        session.markVerified()
        val restored = Session.restore(alice, SessionCodec.decode(SessionCodec.encode(session.snapshot())))
        assertEquals(Trust.VERIFIED, restored.trustLevel)
    }

    @Test
    fun `a stored session holds no plaintext`() {
        // Not a security boundary — the file is in private storage — but a stored ratchet that
        // contained readable message bodies would be a surprise worth catching.
        val session = Session.start(alice, bob.bundle())
        session.seal("meet me at the usual place".toByteArray())
        val text = SessionCodec.encode(session.snapshot())
        assertTrue(text.none { it.code in 0x00..0x08 })
        assertTrue(!text.contains("usual place"))
    }

    @Test
    fun `a session from another version is refused rather than half-read`() {
        val text = SessionCodec.encode(Session.start(alice, bob.bundle()).snapshot())
        assertThrows(SealException::class.java) { SessionCodec.decode(text.replaceFirst("v1", "v9")) }
        assertThrows(SealException::class.java) { SessionCodec.decode("") }
    }

    @Test
    fun `a truncated session never restores as a half-built one`() {
        // Half a ratchet is worse than no ratchet: no ratchet costs one round trip to re-establish,
        // half a ratchet re-derives message keys that have already been used. So every truncation
        // must either be refused outright or come back complete — never partially.
        val text = SessionCodec.encode(Session.start(alice, bob.bundle()).snapshot())
        val lines = text.lines().filter { it.isNotBlank() }
        for (keep in 1 until lines.size) {
            val decoded = runCatching { SessionCodec.decode(lines.take(keep).joinToString("\n")) }
            val snapshot = decoded.getOrNull() ?: continue
            assertEquals(Curve25519.KEY_BYTES, snapshot.peerIdentityKey.size)
            assertEquals(Kdf.HASH_BYTES, snapshot.ratchet.rootKey.size)
            assertEquals(Curve25519.KEY_BYTES, snapshot.ratchet.sendingPrivateKey.size)
            assertEquals(Curve25519.KEY_BYTES, snapshot.ratchet.sendingPublicKey.size)
        }
    }
}
