package com.health.app.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.max

/**
 * Decoding a picked image at a size worth keeping, without ever loading the full-resolution bitmap.
 *
 * The two-pass `inJustDecodeBounds` dance, which is the difference between a hundred kilobytes of
 * working memory and forty megabytes of it. A modern phone camera produces a twelve-megapixel image
 * of a piece of paper; keeping that verbatim makes the store ten times larger and every screen that
 * renders it slower, for detail no reader can use.
 *
 * Shared by [CardImageStore] and [DocumentStore] rather than written twice. They keep different
 * things in different directories for different reasons, but "read a picture the user pointed at,
 * without running out of memory" is one problem with one answer, and two copies of it would drift
 * the first time either was tuned.
 */
internal object ImageDownsampler {

    /**
     * Roughly 1600 on the long edge — about 470 dpi across a credit card, which keeps a member
     * number sharp when somebody zooms an exported PDF. It is generous enough for a page of A4 to
     * stay readable too, which is the other thing households photograph.
     */
    const val DEFAULT_MAX_EDGE_PX = 1600

    const val DEFAULT_JPEG_QUALITY = 85

    /** Null when the picture can't be read — a corrupt file, a lapsed permission, a "photo" that isn't. */
    fun decode(context: Context, source: Uri, maxEdgePx: Int = DEFAULT_MAX_EDGE_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= maxEdgePx) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }
}
