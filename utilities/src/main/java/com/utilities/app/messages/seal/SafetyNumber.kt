package com.utilities.app.messages.seal

import java.security.MessageDigest

/**
 * The sixty digits two people read to each other.
 *
 * ## What it is for
 *
 * Trust on first use means the very first exchange could, in principle, have been intercepted: an
 * attacker sitting between two phones could have handed each of them its own identity key. Nothing
 * in the protocol can detect that, because from the inside it looks exactly like a working session.
 * What detects it is two humans comparing a number that is derived from **both** identity keys —
 * because the attacker's version of the conversation has two different pairs of keys in it, and
 * therefore two different numbers.
 *
 * That is the whole of it, and it is why this is worth building even though most people will never
 * use it: the ones who need it, need it against exactly the attacker the automatic setup admits.
 *
 * ## Why it is slow on purpose
 *
 * Five thousand two hundred rounds of SHA-512. The number is Signal's and the reason is that a
 * fingerprint short enough to read aloud is short enough to attack by brute force — an attacker who
 * can grind out an identity key whose fingerprint matches yours has defeated the check. Making each
 * attempt cost five thousand hashes makes finding a collision in sixty digits cost more than it is
 * worth, while costing the phone about a tenth of a second, once.
 *
 * ## Why the two halves are sorted
 *
 * So both ends produce the same string without agreeing on who is who. Ordering by "mine first"
 * would give the two people different numbers to compare, which is the one failure that would make
 * the feature worse than nothing.
 */
object SafetyNumber {

    /** Signal's iteration count, kept for the reason given above. */
    const val ITERATIONS = 5200

    /** Digits per party. Six groups of five. */
    const val DIGITS = 30

    private const val GROUP_SIZE = 5

    /** Changing this invalidates every number anybody has already compared. */
    private val VERSION = byteArrayOf(0, 0)

    /**
     * One party's half.
     *
     * The identity key is both the thing being fingerprinted and the identifier, because this app
     * has no accounts: there is no stable name for a person other than their key.
     */
    fun fingerprint(identityKey: ByteArray): String {
        if (identityKey.size != Curve25519.KEY_BYTES) throw SealException("that is not an identity key")
        val digest = MessageDigest.getInstance("SHA-512")

        var hash = digest.digest(Bytes.concat(VERSION, identityKey, identityKey))
        repeat(ITERATIONS) {
            digest.reset()
            digest.update(hash)
            digest.update(identityKey)
            hash = digest.digest()
        }

        val builder = StringBuilder(DIGITS)
        for (group in 0 until DIGITS / GROUP_SIZE) {
            builder.append(fiveDigits(hash, group * GROUP_SIZE))
        }
        return builder.toString()
    }

    /**
     * The number for a conversation: both halves, in an order both ends agree on.
     *
     * Grouped into blocks of five when displayed, which is the only reason anybody can read sixty
     * digits aloud without losing their place.
     */
    fun of(ourIdentityKey: ByteArray, theirIdentityKey: ByteArray): String {
        val ours = fingerprint(ourIdentityKey)
        val theirs = fingerprint(theirIdentityKey)
        return if (ours <= theirs) ours + theirs else theirs + ours
    }

    /** The same number, in blocks of five, as it is shown and read out. */
    fun display(number: String): String = number.chunked(GROUP_SIZE).joinToString(" ")

    /** Whether what was scanned or typed matches, ignoring how it was spaced. */
    fun matches(expected: String, scanned: String): Boolean {
        val a = expected.filter { it.isDigit() }
        val b = scanned.filter { it.isDigit() }
        if (a.isEmpty() || a.length != b.length) return false
        return Bytes.constantTimeEquals(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Five decimal digits out of five bytes.
     *
     * Forty bits reduced modulo 100000, which loses a little uniformity and is what the format is.
     * The alternative — rejection sampling for perfectly uniform digits — would make the number
     * depend on how many samples were rejected, and the two ends would have to agree on that too.
     */
    private fun fiveDigits(hash: ByteArray, offset: Int): String {
        var value = 0L
        for (i in 0 until GROUP_SIZE) {
            value = (value shl 8) or (hash[offset + i].toLong() and 0xFF)
        }
        return (value % 100_000L).toString().padStart(GROUP_SIZE, '0')
    }
}
