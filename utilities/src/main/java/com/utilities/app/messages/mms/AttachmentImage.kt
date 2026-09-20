package com.utilities.app.messages.mms

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drawing a picture out of the message store, without an image library.
 *
 * ## Why there is no library here
 *
 * The suite ships no image loader and this is not the feature to add one for. What a thread needs
 * is narrow and known: decode a `content://mms/part/…` URI, at a size worth drawing, off the main
 * thread, and remember the result while the list is scrolled. That is thirty lines and a cache;
 * Coil is a dependency, an initialiser and a disk cache full of other people's photographs.
 *
 * ## The cache
 *
 * Bounded by **memory** rather than by count, because the whole risk here is that a thread of forty
 * photographs holds forty decoded bitmaps and the system kills the process. An eighth of the heap is
 * the usual figure for an image cache and is what this takes. Anything evicted is decoded again,
 * which costs a frame and never costs correctness.
 *
 * Keyed by URI and target size together: the same part drawn as a thumbnail and full-width is two
 * entries, and keying on the URI alone would show one of them at the wrong resolution.
 */
object AttachmentImage {

    private val cache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceAtLeast(4 * 1024)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    /** Decode [uri] at a size worth drawing, from the cache when it is there. */
    suspend fun load(context: Context, uri: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri@$maxPx"
        cache.get(key)?.let { return@withContext it }
        val decoded = MmsImages.preview(context, Uri.parse(uri), maxPx) ?: return@withContext null
        cache.put(key, decoded)
        decoded
    }

    /** Let go of everything, for the settings screen's "free up memory" and for a low-memory callback. */
    fun clear() = cache.evictAll()
}

/**
 * A part's picture, or null while it is being decoded and if it cannot be.
 *
 * Null is a state the caller draws rather than an error: a placeholder of the right shape means a
 * thread scrolls without every bubble changing height as its picture arrives.
 */
@Composable
fun rememberAttachmentBitmap(
    context: Context,
    uri: String,
    maxPx: Int = MmsImages.PREVIEW_MAX_PX
): ImageBitmap? {
    var bitmap by remember(uri, maxPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, maxPx) {
        bitmap = AttachmentImage.load(context, uri, maxPx)?.asImageBitmap()
    }
    return bitmap
}
