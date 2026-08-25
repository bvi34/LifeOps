package com.citation.app.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import com.citation.core.doc.DocumentBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One illustration, drawn into the placeholder the renderer reserved for it. */
@Composable
fun ChapterImage(bitmap: ImageBitmap, contentDescription: String?) {
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
    )
}

/**
 * Loads a chapter's illustrations off the main thread, downsampled to the page.
 *
 * Downsampling is not an optimisation here, it is a correctness matter: a publisher's plate is
 * routinely 2000px wide, and decoding a handful of those at full size to draw them a few hundred
 * pixels across is how a reader runs out of memory on a long chapter. `inSampleSize` decodes
 * straight to roughly the size actually needed.
 *
 * Every failure is silent and local — a book with one unreadable plate should still open, and the
 * renderer already falls back to naming the missing image. Images are freed when the chapter
 * changes rather than accumulating across a book.
 */
@Composable
fun rememberChapterImages(
    blocks: List<DocumentBlock>,
    targetWidthPx: Int,
    resolve: (String) -> File?
): Map<String, ImageBitmap> {
    val sources = remember(blocks) {
        blocks.filterIsInstance<DocumentBlock.Image>().map { it.src }.distinct()
    }
    var loaded by remember(sources, targetWidthPx) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }

    LaunchedEffect(sources, targetWidthPx) {
        if (sources.isEmpty() || targetWidthPx <= 0) {
            loaded = emptyMap()
            return@LaunchedEffect
        }
        loaded = withContext(Dispatchers.IO) {
            sources.mapNotNull { src ->
                val file = resolve(src) ?: return@mapNotNull null
                decode(file, targetWidthPx)?.let { src to it }
            }.toMap()
        }
    }

    DisposableEffect(sources) {
        onDispose { loaded = emptyMap() }
    }

    return loaded
}

private fun decode(file: File, targetWidthPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()
