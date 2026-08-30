package com.people.app.partner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class PartnerInviteCodecTest {

    private val invite = PartnerInvite(
        instanceId = "inst-a",
        displayName = "Marta",
        personKey = "key-marta",
        secret = "sec-a",
        issuedAt = 1_700_000_000_000L
    )

    @Test
    fun `a code round-trips`() {
        assertEquals(invite, PartnerInviteCodec.decode(PartnerInviteCodec.encode(invite)))
    }

    @Test
    fun `a name with punctuation survives the wire`() {
        val awkward = invite.copy(displayName = "Ana: Mum's phone | \"home\"")
        assertEquals(awkward, PartnerInviteCodec.decode(PartnerInviteCodec.encode(awkward)))
    }

    @Test
    fun `surrounding whitespace from a paste is ignored`() {
        val text = "  ${PartnerInviteCodec.encode(invite)}\n"
        assertEquals(invite, PartnerInviteCodec.decode(text))
    }

    @Test
    fun `somebody else's QR code is not a pairing code`() {
        assertNull(PartnerInviteCodec.decode("https://example.com/join"))
        assertNull(PartnerInviteCodec.decode("WIFI:S:home;T:WPA;P:hunter2;;"))
        assertNull(PartnerInviteCodec.decode(""))
    }

    @Test
    fun `a corrupt payload is refused rather than half-read`() {
        assertNull(PartnerInviteCodec.decode("${PartnerWire.PREFIX}:1:not-base64!!"))
        assertNull(PartnerInviteCodec.decode("${PartnerWire.PREFIX}:1:"))
    }

    @Test
    fun `a code from a newer wire is refused`() {
        val newer = PartnerInviteCodec.encode(invite.copy(wire = PartnerWire.VERSION + 1))
        assertNull(PartnerInviteCodec.decode(newer))
    }

    @Test
    fun `both sides compute the same token from opposite directions`() {
        val a = PairSecret.mint(Random(1))
        val b = PairSecret.mint(Random(2))
        assertEquals(PairSecret.token(a, b), PairSecret.token(b, a))
    }

    @Test
    fun `a different pairing gets a different token`() {
        val a = PairSecret.mint(Random(1))
        val b = PairSecret.mint(Random(2))
        val c = PairSecret.mint(Random(3))
        assertNotEquals(PairSecret.token(a, b), PairSecret.token(a, c))
    }

    @Test
    fun `minted secrets are not guessable from a fresh instance`() {
        val secrets = (1..50).map { PairSecret.mint() }
        assertEquals(50, secrets.toSet().size)
    }
}
