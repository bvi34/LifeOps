package com.citation.app.audio

import com.citation.core.speech.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a voice's two files over plain HTTPS — the only network call the whole speech track makes.
 *
 * `HttpURLConnection`, no client library, following [com.citation.app.data.rr.RoyalRoadClient] and
 * the OPDS client: the app carries no HTTP dependency and this is not the place to add one. Redirects
 * are followed by hand for the same reason they are there — the host redirects to a CDN, and the
 * platform's automatic following stops at a protocol change.
 *
 * The config is fetched first. It is four kilobytes against sixty megabytes, so a wrong URL, an
 * expired host or a captive-portal login page costs the reader a moment rather than most of their
 * data allowance before it fails.
 */
class VoiceDownloader(private val store: VoiceStore) {

    /**
     * Download and install [model], reporting `0f`..`1f` as the weights arrive.
     *
     * Returns the failure rather than throwing: this runs behind a download button, and every way it
     * can fail — no connection, a moved file, a full disk, a download cut short — is something to
     * show the reader with the retry still in front of them.
     */
    suspend fun install(
        model: VoiceModel,
        onProgress: (Float) -> Unit = {}
    ): VoiceStore.InstallResult = withContext(Dispatchers.IO) {
        val config = fetch(model.configUrl) { stream ->
            store.write(store.configFile(model), stream)
        }
        if (config is VoiceStore.InstallResult.Failed) return@withContext config

        val total = model.sizeBytes.coerceAtLeast(1L)
        val weights = fetch(model.modelUrl) { stream ->
            store.write(store.modelFile(model), stream, model.md5) { written ->
                onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        // A voice is its pair. Weights that failed leave a config behind that is worth nothing and
        // would otherwise sit in the store forever.
        if (weights is VoiceStore.InstallResult.Failed) store.configFile(model).delete()
        weights
    }

    private fun fetch(
        url: String,
        body: (java.io.InputStream) -> VoiceStore.InstallResult
    ): VoiceStore.InstallResult {
        var current = url
        var redirects = 0
        while (true) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    readTimeout = READ_TIMEOUT_MILLIS
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "*/*")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return VoiceStore.InstallResult.Failed("The voice has moved and left no address")
                    if (++redirects > MAX_REDIRECTS) {
                        return VoiceStore.InstallResult.Failed("Too many redirects fetching the voice")
                    }
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    return VoiceStore.InstallResult.Failed("The voice could not be fetched (HTTP $code)")
                }
                return connection.inputStream.use(body)
            } catch (e: Exception) {
                return VoiceStore.InstallResult.Failed(e.message ?: "The voice could not be fetched")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val MAX_REDIRECTS = 5
    }
}
