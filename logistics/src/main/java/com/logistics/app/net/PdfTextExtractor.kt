package com.logistics.app.net

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one Android-specific step of the Walmart import: pull raw text out of a PDF. Everything that
 * turns that text into pantry lines is the framework-free [com.logistics.app.logic.WalmartOrderParser],
 * so this stays a thin, replaceable I/O shim.
 *
 * Uses the PDFBox-Android port. Its font/resource loader must be initialized once against a context
 * before the first parse; [ensureInit] handles that idempotently.
 */
object PdfTextExtractor {

    @Volatile
    private var initialized = false

    private fun ensureInit(context: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }

    /** Extract all text from the PDF at [uri]. Returns null if the file can't be opened or read. */
    suspend fun extract(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        ensureInit(context)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().getText(doc)
                }
            }
        }.getOrNull()
    }
}
