package com.citation.app.ui.reader

import android.util.Base64
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.citation.core.reader.ReaderSettings
import com.citation.core.reader.ReaderTypeface
import com.citation.core.reader.ReaderWebStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Carries the reader's display settings into a **read-in-place** WebView (Kindle, O'Reilly).
 *
 * Citation caches nothing from those two — the content is licensed and their reader is the real
 * reader — which used to mean the whole Display sheet applied to the EPUB track and to nothing else.
 * A reader who needs a night page, larger type or their own dyslexia face needs it in every book they
 * open, not in the ones that happen to be EPUBs.
 *
 * What is applied, and what is deliberately not, is decided in [ReaderWebStyle] where it can be
 * tested. This is the part that has to touch Android: the text zoom, the font bytes, and the timer.
 */
@Composable
fun ReaderWebStyler(webView: WebView?, settings: ReaderSettings) {
    // Embedding a font means reading and encoding it, so it is done off the main thread and only
    // when the face or the file actually changes — not on every settings tweak.
    val fontFace by produceState<String?>(null, settings.typeface, settings.customFontPath) {
        value = settings.customFontPath
            ?.takeIf { settings.typeface == ReaderTypeface.CUSTOM }
            ?.let { embedFont(it) }
    }

    val script = remember(settings, fontFace) {
        if (settings.styleReadInPlace) {
            ReaderWebStyle.installScript(ReaderWebStyle.stylesheet(settings, fontFace))
        } else {
            // Turning it off is not "stop applying": a stylesheet already in the page has to come
            // back out, or switching off would only take effect on the next open.
            ReaderWebStyle.removeScript()
        }
    }

    LaunchedEffect(webView, script, settings.styleReadInPlace, settings.fontSize) {
        val view = webView ?: return@LaunchedEffect
        // Applied now and then on a slow tick, because both of these readers are single-page apps:
        // they rebuild their own DOM as you turn a page, and a frame that appears later has never
        // seen the stylesheet. The script replaces one known element, so re-running it is free.
        while (true) {
            view.applyReaderStyle(script, settings)
            delay(REAPPLY_MILLIS)
        }
    }
}

/**
 * Apply the reader's settings to one WebView.
 *
 * Size goes through `textZoom` rather than the stylesheet: it scales the site's *own* relative
 * sizing, so a reader that sets its headings in `em` keeps its proportions instead of being flattened
 * to one pixel size. Set only when it changes — the setter forces a relayout.
 */
private fun WebView.applyReaderStyle(script: String, reader: ReaderSettings) {
    val zoom = if (reader.styleReadInPlace) ReaderWebStyle.textZoom(reader) else DEFAULT_ZOOM
    if (settings.textZoom != zoom) settings.textZoom = zoom
    evaluateJavascript(script, null)
}

/**
 * A reader's own font as a `@font-face` rule, or null.
 *
 * The bytes are embedded rather than linked because the page is served over https and its origin
 * cannot reach the filesystem — no `file://` URL will load here, whatever the WebView is allowed to
 * do. That makes the size cap the honest part of this: a font is inlined into a script and sent
 * across the JS bridge, so a 20 MB CJK face is refused rather than freezing the reader with it.
 */
private suspend fun embedFont(path: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(path)
        if (!file.isFile || file.length() !in 1..MAX_EMBEDDED_FONT_BYTES) return@runCatching null
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        ReaderWebStyle.fontFaceRule(encoded, file.extension)
    }.getOrNull()
}

/** How often the stylesheet is put back, for pages that rebuild themselves as you read. */
private const val REAPPLY_MILLIS = 1500L

/** A WebView's own default: no zoom, which is what "leave it alone" has to mean. */
private const val DEFAULT_ZOOM = 100

/** Big enough for any Latin face and most others; small enough to cross the JS bridge safely. */
private const val MAX_EMBEDDED_FONT_BYTES = 2L * 1024 * 1024
