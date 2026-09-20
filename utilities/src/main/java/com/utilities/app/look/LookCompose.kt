package com.utilities.app.look

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bridge between the framework-free look and Compose.
 *
 * Everything decidable without a device is in [UtilityLook] and [UtilityPalettes]; this file is the
 * two things that are not — resolving [UtilityTheme.SYSTEM] against whatever the suite's Material
 * scheme happens to be right now, and loading a font file off the main thread.
 */

/** Plain ARGB as Compose sees it. */
fun Int.toComposeColor(): Color = Color(this)

/** Compose paints in floats; the look's maths speaks `0xAARRGGBB`. */
fun Color.toArgbInt(): Int = toArgb()

/**
 * [look] resolved against the colours the app is using right now.
 *
 * The fallbacks matter: they are what makes "System" mean the suite's own appearance rather than a
 * guess, and they are read from the *Material* scheme here rather than baked in, so recolouring
 * Utilities in the sandbox's gear repaints a keyboard set to follow the system.
 */
@Composable
fun rememberPalette(look: UtilityLook): UtilityPalette {
    val scheme = MaterialTheme.colorScheme
    val surface = scheme.surface.toArgb()
    val text = scheme.onSurface.toArgb()
    val accent = scheme.primary.toArgb()
    return remember(look, surface, text, accent) {
        UtilityPalettes.resolve(look, surface, text, accent)
    }
}

/**
 * The face a surface is set in.
 *
 * A font file that has gone missing, or one the platform cannot load, falls back to sans rather
 * than failing — Citation's rule, and for the same reason: a face is the least important thing
 * about a surface, and a missing one must never make it undrawable.
 */
@Composable
fun rememberLookFontFamily(look: UtilityLook): FontFamily {
    val builtIn = when (look.typeface) {
        UtilityTypeface.SERIF -> FontFamily.Serif
        UtilityTypeface.SANS -> FontFamily.SansSerif
        UtilityTypeface.MONO -> FontFamily.Monospace
        UtilityTypeface.CUSTOM -> FontFamily.SansSerif
    }
    if (look.typeface != UtilityTypeface.CUSTOM) return builtIn

    val path = look.fontPath
    var custom by remember(path) { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(path) { custom = path?.let { loadFont(it) } }
    return custom ?: builtIn
}

/**
 * `Font(File)` parses eagerly and throws on a malformed file, so it is done off the main thread and
 * inside a `runCatching` — a bad font file must not take a screen down with it.
 */
private suspend fun loadFont(path: String): FontFamily? = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return@runCatching null
        FontFamily(Font(file))
    }.getOrNull()
}
