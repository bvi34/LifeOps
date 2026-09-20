package com.operations.vaultkit

import java.util.Base64

/**
 * The offline hand-off used by the Edge companion.
 *
 * The extension puts a fresh, one-use 256-bit key in its request QR. Secrets scans that request,
 * encrypts the already-encrypted `vault.opsv` bytes once more with that key, then shows the result
 * as a numbered series of QRs for the extension to scan. There is deliberately no account,
 * server, socket, or network permission involved. The extension receives the complete sealed
 * vault, never its decrypted document; it still needs the master passphrase before it can fill.
 */
object ExtensionTransfer {
    private const val REQUEST_PREFIX = "lifeops-secrets-edge:1:"
    private const val FRAME_PREFIX = "lifeops-secrets-frame:1:"
    private const val KEY_BYTES = VaultCrypto.KEY_BYTES
    private const val FRAME_BYTES = 600

    data class Request(val session: String, val key: ByteArray)
    data class Frame(val session: String, val index: Int, val count: Int, val payload: String)

    fun createRequest(): Request = Request(randomSession(), VaultCrypto.randomBytes(KEY_BYTES))

    fun requestQr(request: Request): String = "$REQUEST_PREFIX${request.session}:${encode(request.key)}"

    fun parseRequest(text: String): Request? = runCatching {
        val parts = text.removePrefix(REQUEST_PREFIX).split(':')
        require(text.startsWith(REQUEST_PREFIX) && parts.size == 2 && parts[0].length == 22)
        Request(parts[0], decode(parts[1]).also { require(it.size == KEY_BYTES) })
    }.getOrNull()

    /** Encrypt an OPSVAULT file and split its text form into QR-sized, independently scannable frames. */
    fun frames(request: Request, sealedVault: ByteArray): List<String> {
        val sealed = VaultCrypto.seal(request.key, sealedVault, request.session.toByteArray(Charsets.UTF_8))
        val payload = encode(sealed.nonce + sealed.ciphertext)
        return payload.chunked(FRAME_BYTES).mapIndexed { index, chunk ->
            "$FRAME_PREFIX${request.session}:${index + 1}:${(payload.length + FRAME_BYTES - 1) / FRAME_BYTES}:$chunk"
        }
    }

    fun parseFrame(text: String): Frame? = runCatching {
        val parts = text.removePrefix(FRAME_PREFIX).split(':', limit = 5)
        require(text.startsWith(FRAME_PREFIX) && parts.size == 4)
        Frame(parts[0], parts[1].toInt(), parts[2].toInt(), parts[3]).also {
            require(it.session.length == 22 && it.index in 1..it.count && it.count <= 500)
        }
    }.getOrNull()

    /** Reassembles and authenticates frames. Null means a missing, mixed, or tampered transfer. */
    fun open(request: Request, frames: Collection<String>): ByteArray? = runCatching {
        val parsed = frames.mapNotNull(::parseFrame)
        require(parsed.isNotEmpty() && parsed.all { it.session == request.session })
        val count = parsed.first().count
        require(parsed.size == count && parsed.map { it.index }.toSet().size == count && parsed.all { it.count == count })
        val bytes = decode(parsed.sortedBy { it.index }.joinToString("") { it.payload })
        require(bytes.size > VaultCrypto.NONCE_BYTES)
        VaultCrypto.open(request.key, Sealed(bytes.copyOfRange(0, VaultCrypto.NONCE_BYTES), bytes.copyOfRange(VaultCrypto.NONCE_BYTES, bytes.size)), request.session.toByteArray(Charsets.UTF_8))
    }.getOrNull()

    private fun randomSession(): String = encode(VaultCrypto.randomBytes(16))
    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
