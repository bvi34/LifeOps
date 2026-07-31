package com.citation.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.citation.app.data.CitationRepository

/**
 * The **PDF render track**: positioned glyphs don't reflow, so a PDF is rendered *page by page* as a
 * bitmap via the platform `PdfRenderer` — never forced through the flowing-text reader. The page is
 * shown whole (fit to the viewport) and made **gestural**: pinch to zoom, drag to pan while zoomed,
 * and double-tap to toggle a 2.5× zoom at the tapped point. A note here is page-anchored (the core
 * `TextAnchor.Pdf`), captured with the passage quote you paste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(session: CitationRepository.PdfSession, vm: ReaderViewModel) {
    // Open the renderer once for the session; close it on dispose.
    val renderer = remember(session.file.path) {
        runCatching {
            val fd = ParcelFileDescriptor.open(session.file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(fd)
        }.getOrNull()
    }
    DisposableEffect(renderer) {
        onDispose { runCatching { renderer?.close() } }
    }

    var pageIndex by remember { mutableIntStateOf(0) }
    val pageCount = renderer?.pageCount ?: 0
    // Each page turn is a reading-progress signal for the engaged-time meter (idle pages don't count).
    LaunchedEffect(pageIndex) { vm.onReadingProgress() }
    var showNote by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    // Render each page at a generous resolution so pinch-zoom stays crisp rather than pixelated. The
    // long edge is capped so a large page can't blow the bitmap budget.
    val bitmap = remember(renderer, pageIndex) {
        renderer?.takeIf { pageIndex in 0 until it.pageCount }?.let { r ->
            r.openPage(pageIndex).use { page ->
                val target = 2400
                val scale = (target.toFloat() / maxOf(page.width, page.height)).coerceIn(2f, 4f)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF · page ${pageIndex + 1} / $pageCount", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.closePdf() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    IconButton(onClick = { showNote = !showNote }) {
                        Icon(Icons.Default.Add, contentDescription = "Add note")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showNote) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(
                        value = quote, onValueChange = { quote = it },
                        label = { Text("Passage on this page (paste the quote)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        label = { Text("Your note") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showNote = false }) { Text("Cancel") }
                        Button(
                            onClick = { vm.capturePdfNote(pageIndex, quote, body); quote = ""; body = ""; showNote = false },
                            enabled = quote.isNotBlank() && body.isNotBlank(),
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text("Save note") }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    // pageIndex is the key so zoom/pan reset when the page turns.
                    ZoomablePage(key = pageIndex) { imageModifier ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "PDF page ${pageIndex + 1}",
                            modifier = imageModifier,
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    Text("Couldn’t render this PDF.", Modifier.padding(24.dp))
                }
            }

            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text("Previous") }
                OutlinedButton(onClick = { pageIndex++ }, enabled = pageIndex < pageCount - 1) { Text("Next") }
            }
        }
    }
}

/**
 * Wraps [content] (typically an [Image] filling the box) in a pinch-to-zoom / drag-to-pan surface.
 * Zoom is clamped to 1×–5×; panning is clamped so the page can't be dragged off-screen, and a
 * double-tap toggles a 2.5× zoom centred on the tap. [key] resets the transform when it changes
 * (e.g. a new page), so every page starts fitted and centred.
 */
@Composable
private fun ZoomablePage(
    key: Any,
    content: @Composable (Modifier) -> Unit
) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(key) { mutableStateOf(IntSize.Zero) }

    // Clamp translation so at least the page edge stays within the viewport at the current zoom.
    fun clampedOffset(candidate: Offset, s: Float): Offset {
        val maxX = (boxSize.width * (s - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxSize.height * (s - 1f) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(key) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    // Keep the pinch centroid stationary while zooming, then apply the drag pan.
                    val focus = centroid - Offset(boxSize.width / 2f, boxSize.height / 2f)
                    val scaled = offset + (offset - focus) * (newScale / scale - 1f)
                    scale = newScale
                    offset = if (newScale <= 1f) Offset.Zero else clampedOffset(scaled + pan, newScale)
                }
            }
            .pointerInput(key) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val focus = tap - Offset(boxSize.width / 2f, boxSize.height / 2f)
                            scale = target
                            offset = clampedOffset(-focus * (target - 1f), target)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        content(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
