package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generator is checked against RFC 6238's own test vectors, which is the only kind of test worth
 * having here: a second factor that is subtly wrong produces six plausible digits that no site
 * accepts, and no amount of reasoning about the code catches that. If these pass, the arithmetic
 * agrees with every authenticator app in the world.
 */
class TotpTest {

    /** The RFC's seed: the ASCII bytes of "12345678901234567890", Base32-encoded. */
    private val rfcSeed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private fun at(seconds: Long, secret: String = rfcSeed, digits: Int = 8): String? =
        Totp.code(TotpConfig(secret = secret, digits = digits), seconds * 1000L)?.digits

    @Test
    fun `the Base32 seed is the bytes RFC 6238 says it is`() {
        assertEquals("12345678901234567890", String(Base32.decode(rfcSeed)!!, Charsets.US_ASCII))
    }

    @Test
    fun `RFC 6238 test vectors, SHA-1`() {
        assertEquals("94287082", at(59))
        assertEquals("07081804", at(1111111109))
        assertEquals("14050471", at(1111111111))
        assertEquals("89005924", at(1234567890))
        assertEquals("69279037", at(2000000000))
        assertEquals("65353130", at(20000000000))
    }

    @Test
    fun `RFC 6238 test vectors, SHA-256`() {
        val key = "12345678901234567890123456789012".toByteArray(Charsets.US_ASCII)
        fun code(t: Long) = Totp.code(key, t / 30, TotpAlgorithm.SHA256, 8)

        assertEquals("46119246", code(59))
        assertEquals("68084774", code(1111111109))
        assertEquals("67062674", code(1111111111))
        assertEquals("91819424", code(1234567890))
        assertEquals("90698825", code(2000000000))
        assertEquals("77737706", code(20000000000))
    }

    @Test
    fun `RFC 6238 test vectors, SHA-512`() {
        val key = ("1234567890".repeat(6) + "1234").toByteArray(Charsets.US_ASCII)
        assertEquals(64, key.size)
        fun code(t: Long) = Totp.code(key, t / 30, TotpAlgorithm.SHA512, 8)

        assertEquals("90693936", code(59))
        assertEquals("25091201", code(1111111109))
        assertEquals("99943326", code(1111111111))
        assertEquals("93441116", code(1234567890))
        assertEquals("38618901", code(2000000000))
        assertEquals("47863826", code(20000000000))
    }

    @Test
    fun `six digits is the default, and it is the first six of nothing`() {
        // Six digits is the eight-digit code modulo a million — its *last* six, never its first.
        // Taking the first six is the mistake that produces codes which look right and are not.
        val six = at(59, digits = 6)
        assertEquals(6, six!!.length)
        assertEquals("287082", six)
    }

    @Test
    fun `a code keeps its leading zeros`() {
        // 07081804 at T=1111111109. A code formatted as a number rather than as digits loses that
        // zero and is rejected by every site, intermittently, about a tenth of the time.
        assertEquals("07081804", at(1111111109))
        assertEquals(8, at(1111111109)!!.length)
    }

    @Test
    fun `the code holds for a period and changes at the boundary`() {
        val config = TotpConfig(secret = rfcSeed)

        // 1_000_000_020 seconds is a multiple of thirty, so it is the first instant of a period
        // rather than some point inside one — which is what makes the countdown below checkable.
        val early = Totp.code(config, 1_000_000_020_000L)!!
        val late = Totp.code(config, 1_000_000_049_999L)!!
        val next = Totp.code(config, 1_000_000_050_000L)!!

        assertEquals(early.digits, late.digits)
        assertNotEquals(early.digits, next.digits)
        assertEquals(30, early.secondsRemaining)
        assertEquals(1, late.secondsRemaining)
        assertEquals(30, next.secondsRemaining)
    }

    @Test
    fun `the countdown is reported against the seed's own period`() {
        val config = TotpConfig(secret = rfcSeed, periodSeconds = 60)

        // Thirty seconds into a sixty-second period: halfway, on a seed whose issuer chose the
        // longer window.
        val code = Totp.code(config, 90_000L)!!

        assertEquals(60, code.periodSeconds)
        assertEquals(30, code.secondsRemaining)
        assertEquals(0.5f, code.fractionRemaining, 0.001f)
    }

    @Test
    fun `a code is shown in two halves`() {
        assertEquals("123 456", Totp.Code("123456", 10, 30).grouped)
        assertEquals("1234 5678", Totp.Code("12345678", 10, 30).grouped)
    }

    // --- Reading a seed --------------------------------------------------------------------------

    @Test
    fun `a bare Base32 key is accepted, however the site printed it`() {
        val spaced = Totp.parse("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")

        assertNotNull(spaced)
        assertEquals(TotpAlgorithm.SHA1, spaced!!.algorithm)
        assertEquals(6, spaced.digits)
        assertEquals(30, spaced.periodSeconds)
        // Lower case, spacing and hyphens are presentation; the codes are the same either way.
        assertEquals(
            Totp.code(TotpConfig(rfcSeed), 59_000L)?.digits,
            Totp.code(spaced, 59_000L)?.digits
        )
        assertNotNull(Totp.parse("GEZDGNBV-GY3TQOJQ-GEZDGNBV-GY3TQOJQ"))
        assertNotNull("padding is ignored", Totp.parse("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ===="))
    }

    @Test
    fun `an otpauth URI brings its issuer, account and settings with it`() {
        val config = Totp.parse(
            "otpauth://totp/Monzo:brenden%40example.com" +
                "?secret=$rfcSeed&issuer=Monzo&algorithm=SHA256&digits=8&period=60"
        )

        assertNotNull(config)
        assertEquals(rfcSeed, config!!.secret)
        assertEquals(TotpAlgorithm.SHA256, config.algorithm)
        assertEquals(8, config.digits)
        assertEquals(60, config.periodSeconds)
        assertEquals("Monzo", config.issuer)
        assertEquals("brenden@example.com", config.account)
        assertEquals("Monzo · brenden@example.com", config.label)
        assertEquals("SHA256 · 8 digits · 60s", config.unusualSettings)
    }

    @Test
    fun `defaults are filled in for a URI that states none`() {
        val config = Totp.parse("otpauth://totp/Example?secret=$rfcSeed")!!

        assertEquals(TotpAlgorithm.SHA1, config.algorithm)
        assertEquals(6, config.digits)
        assertEquals(30, config.periodSeconds)
        assertEquals("Example", config.account)
        assertNull("the ordinary settings are not worth a line on the screen", config.unusualSettings)
    }

    @Test
    fun `the issuer parameter wins over the one in the label`() {
        val config = Totp.parse("otpauth://totp/Old%20Name:me?secret=$rfcSeed&issuer=Actual%20Bank")!!

        assertEquals("Actual Bank", config.issuer)
        assertEquals("me", config.account)
    }

    @Test
    fun `a plus in an account name survives, which URLDecoder would not manage`() {
        val config = Totp.parse("otpauth://totp/Bank:me+tag%40example.com?secret=$rfcSeed")!!

        assertEquals("me+tag@example.com", config.account)
    }

    @Test
    fun `the SHA-1 spelling with a hyphen is understood`() {
        assertEquals(TotpAlgorithm.SHA1, TotpAlgorithm.fromKey("SHA-1"))
        assertEquals(TotpAlgorithm.SHA256, TotpAlgorithm.fromKey("sha-256"))
        assertNull(TotpAlgorithm.fromKey("MD5"))
        assertNull(TotpAlgorithm.fromKey(null))
    }

    @Test
    fun `what is refused, and why each one has to be`() {
        // Not Base32: 0, 1 and 8 are not in the alphabet, so this would produce wrong codes forever.
        assertNull(Totp.parse("not a real key 0189"))
        assertNull(Totp.parse(""))
        assertNull(Totp.parse("   "))
        // Counter-based. Accepting it would mean a counter that advances when somebody *looks*, so
        // opening the vault would desynchronise the second factor.
        assertNull(Totp.parse("otpauth://hotp/Bank?secret=$rfcSeed&counter=1"))
        // A URI with no seed in it is a label and nothing else.
        assertNull(Totp.parse("otpauth://totp/Bank?issuer=Bank"))
        // Out of range: neither produces a code any site would ask for.
        assertNull(Totp.parse("otpauth://totp/Bank?secret=$rfcSeed&digits=99"))
        assertNull(Totp.parse("otpauth://totp/Bank?secret=$rfcSeed&period=0"))
    }

    @Test
    fun `a stored seed that will not decode yields no code rather than a wrong one`() {
        // What a hand-edited vault, or one merged out of a strange archive, can hold.
        assertNull(Totp.code(TotpConfig(secret = "!!!!"), 59_000L))
        assertNull(Totp.code(TotpConfig(secret = ""), 59_000L))
    }

    @Test
    fun `decoding is strict about content and lenient about presentation`() {
        assertNotNull(Base32.decode("MZXW6==="))
        assertNotNull(Base32.decode("mzxw6"))
        assertNotNull(Base32.decode("MZ XW-6"))
        assertNull("1 and 8 are not Base32 characters", Base32.decode("MZXW1"))
        assertNull(Base32.decode(""))
        assertTrue(Base32.decode("MZXW6")!!.isNotEmpty())
    }
}
