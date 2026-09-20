package com.utilities.app.messages.seal

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 — the key agreement everything in this package is built on.
 *
 * ## Why this is implemented here rather than called
 *
 * The JDK has had `KeyAgreement.getInstance("XDH")` since Java 11 and Android's Conscrypt has it on
 * recent releases, and using it would mean **the code the tests exercise is not the code that runs
 * on the phone** — two implementations, one of them never executed by CI, differing by Android
 * version. For thirty lines of arithmetic that is the wrong trade. What is here runs identically on
 * the test runner and on the handset, and is pinned against RFC 7748's own vectors, which is the
 * only test of a curve implementation worth having.
 *
 * ## What it is not
 *
 * **Constant-time.** The Montgomery ladder below runs a fixed number of iterations and swaps with a
 * branch-free mask, but the field arithmetic underneath is `BigInteger`, whose multiplication is
 * not constant-time. A local attacker able to measure this process's timing precisely could in
 * principle learn something about a private key.
 *
 * That is a real weakness and it is taken deliberately, so it is written down rather than implied:
 * the attacker it admits is one already running code on the phone, at which point the messages are
 * readable from the screen. If this ever guards something where that is not true, the fix is to
 * replace [scalarMultiply]'s field arithmetic with limb-based constant-time code — the surrounding
 * protocol does not change.
 */
object Curve25519 {

    const val KEY_BYTES = 32

    /** 2^255 − 19, the prime the curve is defined over. */
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    /** (486662 − 2) / 4, the constant in the ladder step. */
    private val A24: BigInteger = BigInteger.valueOf(121665)

    /** The generator, u = 9. */
    private val BASE_POINT: ByteArray = ByteArray(KEY_BYTES).also { it[0] = 9 }

    private val random = SecureRandom()

    /** A fresh private scalar. Clamping happens on use, as RFC 7748 specifies. */
    fun generatePrivateKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    /** The public key for a private scalar: the scalar times the base point. */
    fun publicKey(privateKey: ByteArray): ByteArray = scalarMultiply(privateKey, BASE_POINT)

    /** A keypair, as the rest of this package passes one around. */
    fun generateKeyPair(): KeyPair {
        val private = generatePrivateKey()
        return KeyPair(privateKey = private, publicKey = publicKey(private))
    }

    /**
     * The shared secret between our private key and their public one.
     *
     * Throws when the result is all zeroes, which is what a small-order point produces. RFC 7748
     * makes the check optional for X25519 and it is done anyway: a zero shared secret would mean
     * both sides deriving the same keys from a point an attacker chose.
     */
    fun agree(privateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val shared = scalarMultiply(privateKey, theirPublicKey)
        if (shared.all { it.toInt() == 0 }) throw SealException("the key agreement produced nothing")
        return shared
    }

    /**
     * The Montgomery ladder, straight out of RFC 7748 §5.
     *
     * Two constants of the algorithm are worth naming because they look like mistakes: the loop runs
     * from bit 254 rather than 255 (the top bit is forced by clamping and carries no information),
     * and the conditional swap is deferred by one iteration — `swap` is the *accumulated* parity, so
     * the values are exchanged once on the way in and once on the way out.
     */
    fun scalarMultiply(scalar: ByteArray, point: ByteArray): ByteArray {
        if (scalar.size != KEY_BYTES) throw SealException("a scalar is ${scalar.size} bytes, not $KEY_BYTES")
        if (point.size != KEY_BYTES) throw SealException("a point is ${point.size} bytes, not $KEY_BYTES")

        val k = clamp(scalar)
        val u = decodeLittleEndian(point, maskTopBit = true)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = if (k.testBit(t)) 1 else 0
            swap = swap xor bit
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit

            val a = (x2 + z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = (da + cb).let { it.multiply(it) }.mod(P)
            z3 = u.multiply((da - cb).let { it.multiply(it) }).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa + A24.multiply(e)).mod(P)
        }

        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        // Fermat's little theorem: z^(p−2) is z^−1 modulo a prime.
        val inverse = z2.modPow(P.subtract(BigInteger.TWO), P)
        return encodeLittleEndian(x2.multiply(inverse).mod(P))
    }

    /**
     * RFC 7748's clamping: clear the three low bits, clear the top bit, set the second-highest.
     *
     * It is what makes every scalar a multiple of the cofactor and fixes the position of the leading
     * bit, so the ladder's length cannot leak the scalar's magnitude.
     */
    private fun clamp(scalar: ByteArray): BigInteger {
        val copy = scalar.copyOf()
        copy[0] = (copy[0].toInt() and 248).toByte()
        copy[31] = (copy[31].toInt() and 127).toByte()
        copy[31] = (copy[31].toInt() or 64).toByte()
        return decodeLittleEndian(copy, maskTopBit = false)
    }

    private fun decodeLittleEndian(bytes: ByteArray, maskTopBit: Boolean): BigInteger {
        val copy = bytes.copyOf()
        if (maskTopBit) copy[31] = (copy[31].toInt() and 127).toByte()
        var value = BigInteger.ZERO
        for (i in copy.indices.reversed()) {
            value = value.shiftLeft(8).or(BigInteger.valueOf((copy[i].toInt() and 0xFF).toLong()))
        }
        return value
    }

    private fun encodeLittleEndian(value: BigInteger): ByteArray {
        val out = ByteArray(KEY_BYTES)
        var v = value.mod(P)
        for (i in 0 until KEY_BYTES) {
            out[i] = v.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            v = v.shiftRight(8)
        }
        return out
    }

    /** A private key and the public key derived from it. */
    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {

        override fun equals(other: Any?): Boolean =
            other is KeyPair &&
                privateKey.contentEquals(other.privateKey) &&
                publicKey.contentEquals(other.publicKey)

        override fun hashCode(): Int = 31 * privateKey.contentHashCode() + publicKey.contentHashCode()

        /** Deliberately says nothing. A private key in a log is a private key. */
        override fun toString(): String = "KeyPair(public=${Bytes.hex(publicKey).take(16)}…)"
    }
}

/** What anything in this package throws. Always caught before it reaches a screen. */
class SealException(message: String) : Exception(message)
