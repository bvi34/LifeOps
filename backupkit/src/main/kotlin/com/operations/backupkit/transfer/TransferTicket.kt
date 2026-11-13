package com.operations.backupkit.transfer

import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Everything the new phone needs to find the old one and read what it sends, in one QR code.
 *
 * ## Why the QR is a URL, and why the key is after the `#`
 *
 * The same code is scanned twice. The first time is by the new phone's own camera app, before the
 * suite is installed: it sees a URL, opens it in the browser, and the browser asks the old phone for
 * a landing page with the APK on it. The second time is from inside the freshly installed suite,
 * which reads the same URL to find the old phone again and to learn the key the backup was sealed
 * with.
 *
 * The key travels in the URL's *fragment*. A browser never sends the fragment to the server it is
 * talking to, so the first scan, which only needs the APK, does not put the key on the network.
 * The bundle the second scan downloads is useless without it. What the network sees is a path with
 * an unguessable [token] in it, which lets someone fetch the APK and nothing else.
 *
 * ## What a leaked code is worth
 *
 * Very little, and only for a short time. The code works only while the old phone's transfer
 * screen is open and its timer is running. The server lives as long as that screen does. Even
 * while it runs, the data does not leave until somebody taps **Allow** on the old phone, next to a
 * code they have checked matches the one on the new phone (see [confirmationCode]). After that,
 * the decryption key is needed to read it.
 */
data class TransferTicket(
    /** The old phone's address on the local network: an IPv4 literal, or IPv6 without brackets. */
    val host: String,
    val port: Int,
    /** 128 random bits, base64url. The only thing in the path, and the only thing the server checks. */
    val token: String,
    /** The AES-256 key the bundle is sealed with. Never sent over the network; see the note above. */
    val key: ByteArray,
    /** When the old phone stops serving, in epoch millis. Carried so the new phone can say so first. */
    val expiresAt: Long
) {

    /** The path every route on the old phone lives under: `/t/<token>/`. */
    val pathPrefix: String get() = "/t/$token/"

    private val hostLiteral: String get() = if (host.contains(':')) "[$host]" else host

    /** Where the landing page is, without the fragment. What the browser actually requests. */
    val landingUrl: String get() = "http://$hostLiteral:$port$pathPrefix"

    /** What the QR code holds. */
    fun url(): String = "$landingUrl#$KEY_PARAM=${b64(key)}&$EXPIRY_PARAM=${expiresAt / 1000}"

    fun isExpired(now: Long): Boolean = now >= expiresAt

    /** A ticket printed without its key, in case one ever reaches a log line or an exception. */
    override fun toString(): String = "TransferTicket($landingUrl, expiresAt=$expiresAt)"

    override fun equals(other: Any?): Boolean =
        other is TransferTicket && host == other.host && port == other.port && token == other.token &&
            key.contentEquals(other.key) && expiresAt == other.expiresAt

    override fun hashCode(): Int = (host.hashCode() * 31 + port) * 31 + token.hashCode()

    companion object {

        /** How long a transfer screen serves before it closes by itself. */
        const val DEFAULT_TTL_MS: Long = 10 * 60 * 1000L

        private const val TOKEN_BYTES = 16
        private const val KEY_BYTES = 32
        private const val KEY_PARAM = "k"
        private const val EXPIRY_PARAM = "e"
        private val TOKEN_SHAPE = Regex("[A-Za-z0-9_-]{22}")

        fun mint(
            host: String,
            port: Int,
            now: Long,
            ttlMs: Long = DEFAULT_TTL_MS,
            random: SecureRandom = SecureRandom()
        ): TransferTicket {
            val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            val key = ByteArray(KEY_BYTES).also(random::nextBytes)
            return TransferTicket(host, port, b64(token), key, now + ttlMs)
        }

        /**
         * Read a scanned code back, or null if it isn't one of ours.
         *
         * Strict on purpose. The new phone acts on what this returns by connecting to an address
         * on the local network, so anything that is not exactly the shape [url] writes is refused
         * rather than interpreted.
         */
        fun parse(text: String): TransferTicket? = runCatching {
            val uri = URI(text.trim())
            if (uri.scheme != "http") return null
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() } ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            val segments = uri.rawPath.orEmpty().split('/')
            // "", "t", token, "" : exactly /t/<token>/
            if (segments.size != 4 || segments[0] != "" || segments[1] != "t" || segments[3] != "") return null
            val token = segments[2].takeIf { TOKEN_SHAPE.matches(it) } ?: return null
            val params = uri.rawFragment.orEmpty().split('&')
                .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
            val key = params[KEY_PARAM]?.let { Base64.getUrlDecoder().decode(it) } ?: return null
            if (key.size != KEY_BYTES) return null
            val expiresAt = params[EXPIRY_PARAM]?.toLongOrNull()?.times(1000) ?: return null
            TransferTicket(host, port, token, key, expiresAt)
        }.getOrNull()

        /** A fresh per-device nonce for the new phone: 128 bits, lower-case hex. */
        fun newNonce(random: SecureRandom = SecureRandom()): String =
            ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /** The shape [newNonce] produces. The server refuses anything else. */
        fun isNonce(value: String?): Boolean = value != null && value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

        /**
         * The six digits both phones show before any data moves.
         *
         * Each side computes it on its own: the old phone from the key it minted and the nonce the
         * new phone sent, and the new phone from the key it scanned and the nonce it chose. The
         * key is never on the network, so a third device on the same Wi-Fi that sends its own nonce
         * cannot know the code its request would show. The person tapping **Allow** compares two
         * screens, and a mismatch means a request from a phone that isn't theirs.
         */
        fun confirmationCode(key: ByteArray, nonce: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            val digest = mac.doFinal("operations-transfer-confirm:$nonce".toByteArray(Charsets.UTF_8))
            val value = ((digest[0].toInt() and 0x7f) shl 24) or ((digest[1].toInt() and 0xff) shl 16) or
                ((digest[2].toInt() and 0xff) shl 8) or (digest[3].toInt() and 0xff)
            val six = (value % 1_000_000).toString().padStart(6, '0')
            return six.substring(0, 3) + " " + six.substring(3)
        }

        private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
