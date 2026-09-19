package com.operations.vaultkit

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The six digits that change every thirty seconds.
 *
 * ## Why this belongs in a vault that cannot reach the network
 *
 * A time-based one-time password is the rare second factor that is *arithmetic*: HMAC over a counter
 * derived from the clock, truncated to six digits (RFC 6238, and RFC 4226 underneath it). There is
 * no server to ask, no account to have, nothing to sync. Everything this file needs is in
 * `javax.crypto`, which is the same restriction [VaultEnvelope] accepts and for the same reason — a
 * format the household's secrets outlive has to be one any build can read.
 *
 * So the app that promises it cannot phone anybody is, awkwardly, the natural home for this. The
 * alternative the household has today is a separate authenticator app whose seeds live in *its*
 * store, die with the phone, and are the one credential nobody can reissue without talking to a
 * bank's support line.
 *
 * ## What is stored, and what is not
 *
 * The **seed** is stored — that is the secret, and it is treated as one: never searched
 * ([VaultItem.matches] does not look at it), never in the audit's reuse comparison, and gone from a
 * tombstone like everything else. The **code** is never stored, because it is a pure function of the
 * seed and the clock; computing it costs one HMAC and keeping it would mean writing a secret to disk
 * in order to save nothing.
 *
 * ## Two honest limits
 *
 * **The clock is the phone's.** A phone thirty seconds out of step produces codes a site rejects,
 * and there is nothing here that can tell the difference between that and a wrong seed — asking a
 * time server would mean the network permission this module does not have. The screen says the
 * clock is the input, which is the most that can be done about it.
 *
 * **There is no scanner.** Reading a QR code needs the camera, and the camera needs a permission
 * this module's manifest does not declare and will not start declaring for a convenience. Every site
 * that shows a QR code offers the same seed as text behind a "can't scan it?" link; that text is
 * what [parse] takes, in either of the two shapes it comes in.
 */
object Totp {

    /** What a code looks like when it is asked for, and how much life it has left. */
    data class Code(
        val digits: String,
        val secondsRemaining: Int,
        val periodSeconds: Int
    ) {
        /** How much of the period is left, for a bar that empties rather than a number that ticks. */
        val fractionRemaining: Float
            get() = if (periodSeconds <= 0) 0f else secondsRemaining.toFloat() / periodSeconds

        /**
         * `123 456` — split down the middle, because a six-digit number read off a screen and typed
         * into another app is read in two halves whether or not anybody helps.
         */
        val grouped: String
            get() = if (digits.length < 6) digits else {
                val half = digits.length / 2
                "${digits.take(half)} ${digits.drop(half)}"
            }
    }

    /**
     * Read a seed, in either shape a site hands one over.
     *
     * An `otpauth://totp/...` URI (what is inside the QR code, and what the "can't scan it?" link
     * usually copies) carries the issuer, the account and any non-default digits, period or hash —
     * so those are taken from it rather than guessed. A bare Base32 string is the other half of what
     * sites show, and gets the defaults every authenticator assumes: SHA-1, six digits, thirty
     * seconds.
     *
     * Returns null for anything that will not produce codes — including an `otpauth://hotp/` URI,
     * which is counter-based rather than time-based. Accepting one would mean storing a counter that
     * advances every time a code is *looked at*, and a vault that silently desynchronises a second
     * factor by being opened is worse than one that says it cannot hold it.
     */
    fun parse(raw: String): TotpConfig? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith(URI_SCHEME, ignoreCase = true)) {
            parseUri(trimmed)
        } else {
            // Sites print the seed in readable groups — `abcd efgh ijkl` — and people paste what
            // they see. The Base32 decoder ignores the spacing rather than failing on it.
            TotpConfig(secret = trimmed).takeIf { it.isUsable }
        }
    }

    /**
     * The code for [config] at [nowMillis], or null if the seed will not decode.
     *
     * Null is a real answer rather than a crash: a vault merged from an archive, or hand-edited
     * during a recovery, can hold a seed that is not Base32, and the screen showing it should say so
     * instead of disappearing.
     */
    fun code(config: TotpConfig, nowMillis: Long): Code? {
        if (!config.isUsable) return null
        val key = Base32.decode(config.secret) ?: return null
        val period = config.periodSeconds
        val seconds = Math.floorDiv(nowMillis, 1000L)
        val counter = Math.floorDiv(seconds, period.toLong())
        val digits = code(key, counter, config.algorithm, config.digits)
        VaultCrypto.wipe(key)
        val elapsed = Math.floorMod(seconds, period.toLong()).toInt()
        return Code(
            digits = digits,
            secondsRemaining = period - elapsed,
            periodSeconds = period
        )
    }

    /**
     * HOTP (RFC 4226) over [key] and [counter] — the arithmetic both this and every authenticator
     * app run, exposed because it is what the RFC's test vectors are stated in terms of.
     */
    fun code(key: ByteArray, counter: Long, algorithm: TotpAlgorithm, digits: Int): String {
        val message = ByteArray(8)
        var remaining = counter
        for (i in 7 downTo 0) {
            message[i] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }

        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key, algorithm.macName))
        val hash = mac.doFinal(message)

        // Dynamic truncation: the low nibble of the last byte picks where to read four bytes from,
        // and the top bit of those is masked off so the result is positive on every platform that
        // has ever had a signed 32-bit integer.
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        VaultCrypto.wipe(hash)

        val modulus = POWERS_OF_TEN[digits]
        return (binary % modulus).toString().padStart(digits, '0')
    }

    private fun parseUri(uri: String): TotpConfig? {
        val withoutScheme = uri.substring(URI_SCHEME.length)
        // `otpauth://totp/Label?query`. The type is the first path segment and the only one this
        // module answers for; hotp is refused above rather than half-supported.
        if (!withoutScheme.startsWith("totp/", ignoreCase = true)) return null

        val body = withoutScheme.substring("totp/".length)
        val split = body.indexOf('?')
        val label = percentDecode(if (split < 0) body else body.substring(0, split))
        val query = if (split < 0) "" else body.substring(split + 1)

        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            params[percentDecode(pair.substring(0, eq)).lowercase()] = percentDecode(pair.substring(eq + 1))
        }

        val secret = params["secret"]?.takeIf { it.isNotBlank() } ?: return null

        // The label is `Issuer:account` by convention, and the issuer is *also* a query parameter.
        // Where both exist the parameter wins: it is the one that is unambiguously encoded, while
        // the label's colon may or may not have survived whatever copied it.
        val labelIssuer = label.substringBefore(':', missingDelimiterValue = "").trim()
        val account = label.substringAfter(':', missingDelimiterValue = label).trim()

        return TotpConfig(
            secret = secret,
            algorithm = TotpAlgorithm.fromKey(params["algorithm"]) ?: TotpAlgorithm.SHA1,
            digits = params["digits"]?.toIntOrNull() ?: TotpConfig.DEFAULT_DIGITS,
            periodSeconds = params["period"]?.toIntOrNull() ?: TotpConfig.DEFAULT_PERIOD_SECONDS,
            issuer = params["issuer"]?.trim().orEmpty().ifEmpty { labelIssuer },
            account = account
        ).takeIf { it.isUsable }
    }

    /**
     * `%XX` decoding, written out rather than reached for.
     *
     * `URLDecoder` turns `+` into a space, which is right for a form body and wrong for a URI — and
     * an account name with a plus in it (`me+bank@example.com`) is common enough that the difference
     * would be noticed by exactly the people least able to explain it.
     */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private const val URI_SCHEME = "otpauth://"

    private val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)
}

/**
 * Which hash a seed was issued for.
 *
 * Almost always SHA-1, which sounds alarming and is not: HMAC-SHA-1 is unbroken, and a six-digit
 * code that lives thirty seconds is not where a collision attack would be spent anyway. The other
 * two exist because a handful of issuers use them and a seed that cannot be held is a seed that
 * stays in somebody else's app.
 */
enum class TotpAlgorithm(val key: String, val macName: String) {
    SHA1("SHA1", "HmacSHA1"),
    SHA256("SHA256", "HmacSHA256"),
    SHA512("SHA512", "HmacSHA512");

    companion object {
        /** Tolerant of the `SHA-1` spelling, which some issuers use in the URI. */
        fun fromKey(key: String?): TotpAlgorithm? {
            val cleaned = key?.replace("-", "")?.trim() ?: return null
            return entries.firstOrNull { it.key.equals(cleaned, ignoreCase = true) }
        }
    }
}

/**
 * One second factor, as it is stored on a [VaultItem].
 *
 * The seed is kept as the Base32 string the site handed over rather than as decoded bytes, for the
 * same reason the vault document is JSON: this is the form a person can read back to a support line,
 * type into another authenticator, or recognise as the thing they were given. Decoding happens per
 * code and the bytes are wiped after.
 */
data class TotpConfig(
    val secret: String,
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val digits: Int = DEFAULT_DIGITS,
    val periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
    /** Who issued it — `Monzo`. Shown, never searched. */
    val issuer: String = "",
    /** Which account there — `brenden@example.com`. Shown, never searched. */
    val account: String = ""
) {

    /** Will this produce codes? Checked before storing, so a stored config is always usable. */
    val isUsable: Boolean
        get() = secret.isNotBlank() &&
            digits in MIN_DIGITS..MAX_DIGITS &&
            periodSeconds in MIN_PERIOD..MAX_PERIOD &&
            Base32.decode(secret) != null

    /** `Monzo · brenden@example.com`, or whichever half exists. */
    val label: String
        get() = listOf(issuer, account).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * What a screen says about a seed whose settings are not the ones every authenticator assumes.
     * Null when they are, because "SHA1, 6 digits, 30 seconds" on every row is noise.
     */
    val unusualSettings: String?
        get() {
            val parts = buildList {
                if (algorithm != TotpAlgorithm.SHA1) add(algorithm.key)
                if (digits != DEFAULT_DIGITS) add("$digits digits")
                if (periodSeconds != DEFAULT_PERIOD_SECONDS) add("${periodSeconds}s")
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

    companion object {
        const val DEFAULT_DIGITS = 6
        const val DEFAULT_PERIOD_SECONDS = 30
        const val MIN_DIGITS = 6
        const val MAX_DIGITS = 8
        const val MIN_PERIOD = 5
        const val MAX_PERIOD = 300
    }
}

/**
 * Base32 (RFC 4648), decode only.
 *
 * Nothing here encodes: a seed arrives already encoded and is stored exactly as it arrived, so the
 * encoder would be a function with no callers and one more place to be wrong.
 *
 * Deliberately **lenient about presentation and strict about content**. Sites print seeds
 * lower-case, in groups of four, sometimes with hyphens and sometimes with the padding left on, and
 * people paste what they see — so spacing, case and `=` are ignored. A character that is not in the
 * alphabet is not a presentation difference, it is a seed that will produce wrong codes forever, and
 * that returns null so the screen can say "that is not a key" while it is still fixable.
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(encoded: String): ByteArray? {
        var buffer = 0
        var bits = 0
        val out = ByteArrayOutputStream(encoded.length * 5 / 8 + 1)

        for (raw in encoded) {
            if (raw == '=' || raw == '-' || raw == '_' || raw.isWhitespace()) continue
            val value = ALPHABET.indexOf(raw.uppercaseChar())
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }

        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes
    }
}
