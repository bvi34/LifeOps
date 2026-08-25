package com.citation.app.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReaderTypeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The face a book is set in.
 *
 * Citation ships no font of its own beyond the platform's, because the faces readers actually ask
 * for here — OpenDyslexic first among them — are ones it has no right to redistribute. Pointing at
 * a file you already have is both the honest answer and the more capable one: it covers dyslexia
 * faces, a preferred serif, a face with the script your book is in, without Citation curating any
 * of them.
 *
 * A font file that has gone missing, or one the platform cannot load, falls back to sans rather
 * than failing: a book must stay openable, and a face is the least important thing about it.
 */
@Composable
fun rememberReaderFontFamily(settings: ReaderSettings): FontFamily {
    val builtIn = when (settings.typeface) {
        ReaderTypeface.SERIF -> FontFamily.Serif
        ReaderTypeface.SANS -> FontFamily.SansSerif
        ReaderTypeface.MONO -> FontFamily.Monospace
        ReaderTypeface.CUSTOM -> FontFamily.SansSerif
    }
    if (settings.typeface != ReaderTypeface.CUSTOM) return builtIn

    val path = settings.customFontPath
    var custom by remember(path) { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(path) {
        custom = path?.let { loadFont(it) }
    }
    return custom ?: builtIn
}

/**
 * Load a font file into a family, off the main thread.
 *
 * `Font(File)` parses the file eagerly, so doing it during composition would stall a frame on every
 * font change; and a malformed file throws, which is exactly the case that must not take the reader
 * down with it.
 */
private suspend fun loadFont(path: String): FontFamily? = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(path)
        if (!file.exists() || file.length() <= 0) return@runCatching null
        FontFamily(Font(file))
    }.getOrNull()
}

/** A readable name for a stored font file, for the picker. */
fun fontDisplayName(path: String): String =
    File(path).name.substringBeforeLast('.').take(24)
