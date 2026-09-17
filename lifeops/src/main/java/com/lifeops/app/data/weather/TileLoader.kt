package com.lifeops.app.data.weather

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches map tiles and remembers them — the only class in the radar stack that touches the
 * network, exactly as [NwsClient] is for forecasts. Built on `HttpURLConnection` for the same
 * reason: the whole weather feature has no image or HTTP library behind it, and one radar screen
 * is not a reason to grow one.
 *
 * A failure is a `null`, never an exception. A tile that doesn't load is a square of empty map —
 * the pin, the base layer, and every other tile still draw — which is the right failure for a
 * picture made of two hundred independent pieces.
 *
 * The cache is in-memory and sized in bytes rather than entries, because a 256-pixel tile is a
 * predictable ~256 KB decoded and a screen's worth is a few dozen of them. It lives as long as the
 * radar screen's ViewModel does: panning back over ground you've already seen is free, and closing
 * the screen gives every byte back. Nothing is written to disk — radar is a *now* picture, and a
 * cached one would only ever be wrong.
 */
class TileLoader(
    private val userAgent: String = NwsClient.DEFAULT_USER_AGENT,
    cacheBytes: Int = DEFAULT_CACHE_BYTES
) {

    private val cache = object : LruCache<String, ImageBitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * BYTES_PER_PIXEL
    }

    /** A tile already in memory, or null — the synchronous read a draw pass makes. */
    fun cached(url: String): ImageBitmap? = cache.get(url)

    /**
     * The tile at [url], from cache when possible and from the network otherwise. Returns null on
     * any failure (offline, 404 outside a product's coverage, an error page served as HTML) — all
     * of which are ordinary on a map, none of which should reach the user as an error.
     */
    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { fetch(url) } ?: return null
        cache.put(url, bitmap)
        return bitmap
    }

    /** Drop everything — what a manual refresh does, so radar is re-fetched rather than re-shown. */
    fun clear() = cache.evictAll()

    private fun fetch(url: String): ImageBitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "image/png,image/*")
                // Both services this talks to require a descriptive agent; a blank or default one
                // is refused outright by the OSM tile policy.
                setRequestProperty("User-Agent", userAgent)
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val BYTES_PER_PIXEL = 4
        private const val TIMEOUT_MS = 15_000

        /**
         * Room for roughly a screen and a half of tiles at both layers — enough that a pan back to
         * where you started never re-fetches, small enough to be unremarkable next to the bitmaps
         * a Compose screen already holds.
         */
        const val DEFAULT_CACHE_BYTES = 24 * 1024 * 1024
    }
}
