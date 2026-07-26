package com.citation.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.citation.app.data.CitationRepository

/**
 * The **PDF render track**: positioned glyphs don't reflow, so a PDF is rendered *page by page* as a
 * bitmap via the platform `PdfRenderer` — never forced through the flowing-text reader. A note here
 * is page-anchored (the core `TextAnchor.Pdf`), captured with the passage quote you paste; a full
 * glyph-selection layer (quads from tapped text) is a later refinement on top of this same anchor.
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
    var showNote by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    val bitmap = remember(renderer, pageIndex) {
        renderer?.takeIf { pageIndex in 0 until it.pageCount }?.let { r ->
            r.openPage(pageIndex).use { page ->
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
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

            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "PDF page ${pageIndex + 1}",
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        contentScale = ContentScale.FillWidth
                    )
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
