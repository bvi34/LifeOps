package com.citation.core.reader

import kotlin.math.roundToInt

/**
 * The reader's settings, as a stylesheet for the **read-in-place** tracks.
 *
 * Kindle and O'Reilly are read inside their own web readers, because the content is licensed and
 * Citation caches none of it. That has always meant a reader who set up sepia, a larger size and
 * their own face got it in the EPUB track and nowhere else — the settings existed and simply did not
 * reach three quarters of the app. This is what carries them across.
 *
 * It is a **guest in someone else's page**, and the scope reflects that:
 *
 *  - **Size is not here.** It goes through `WebSettings.textZoom`, which scales the site's own
 *    relative sizing instead of overriding it with pixels — a `font-size` in px flattens a reader
 *    that sets its headings in `em` and is the fastest way to break somebody's book.
 *  - **Colours** are applied to the whole surface. A reader who chose a night page means the page,
 *    not a dark column inside a white frame.
 *  - **Typography** is applied to prose elements only, never to `div` or `*`. Restyling every box on
 *    the page resizes their toolbar, their menus and their page-turn controls along with the text.
 *  - **Margins are deliberately absent.** They are the setting most likely to break a fixed-layout
 *    reader and the least missed inside one that already manages its own measure.
 *
 * All of which is why it is worth generating here, in a module with no Android in it: what the CSS
 * says is a decision, and decisions belong somewhere they can be read and tested.
 */
object ReaderWebStyle {

    /** The id the injected `<style>` carries, so it can be found, replaced and removed again. */
    const val STYLE_ID = "citation-reader-style"

    /** The family name a reader's own font is installed under. */
    const val FONT_FAMILY = "CitationReaderFont"

    /** Elements that carry prose. Deliberately not `div`, and never `*`. */
    private const val PROSE =
        "p, li, dd, dt, blockquote, td, th, figcaption, h1, h2, h3, h4, h5, h6"

    /**
     * Map a reader's text size onto a WebView text-zoom percentage.
     *
     * Relative to the default size rather than absolute, because that is what a zoom is: a reader on
     * the default has asked for no change at all and should get exactly the site's own typography.
     */
    fun textZoom(settings: ReaderSettings): Int =
        (settings.fontSize / DEFAULT_FONT_SIZE * 100f).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * The stylesheet for these settings, or an empty string when there is nothing to say.
     *
     * [fontFace] is an optional `@font-face` rule installing the reader's own font (see
     * [fontFaceRule]); without it, a reader set to their own face falls back to sans, exactly as the
     * EPUB track does when a font file has gone.
     */
    fun stylesheet(settings: ReaderSettings, fontFace: String? = null): String {
        val rules = StringBuilder()
        fontFace?.takeIf { it.isNotBlank() }?.let { rules.append(it).append("\n") }

        // Null under the System theme, which means "use the app's own colours" — and inside somebody
        // else's reader the honest reading of that is to leave their colours alone.
        ReaderPalette.of(settings)?.let { (background, foreground) ->
            val bg = hex(background)
            val fg = hex(foreground)
            rules.append("html, body { background: $bg !important; color: $fg !important; }\n")
            rules.append("$PROSE { color: $fg !important; }\n")
        }

        val prose = mutableListOf<String>()
        family(settings, fontFace)?.let { prose += "font-family: $it !important" }
        prose += "line-height: ${trim(settings.lineSpacing)} !important"
        if (settings.letterSpacing != 0f) {
            prose += "letter-spacing: ${trim(settings.letterSpacing)}em !important"
        }
        prose += "text-align: ${if (settings.justify) "justify" else "start"} !important"
        val hyphens = if (settings.hyphenate) "auto" else "manual"
        prose += "-webkit-hyphens: $hyphens !important"
        prose += "hyphens: $hyphens !important"
        rules.append("$PROSE { ${prose.joinToString("; ")}; }\n")

        return rules.toString()
    }

    /**
     * An `@font-face` rule installing a reader's own font from its bytes.
     *
     * A `file://` URL cannot be used here, and not for want of a permission: the page is served over
     * https, so its origin may not reach the filesystem at all. Embedding the bytes is the only route
     * that exists — which is why the caller is expected to refuse a font too large to be worth
     * inlining rather than this deciding for them.
     */
    fun fontFaceRule(base64: String, format: String): String =
        "@font-face { font-family: '$FONT_FAMILY'; " +
            "src: url(data:font/$format;base64,$base64) format('${cssFormat(format)}'); " +
            "font-display: swap; }"

    /** The CSS `format()` keyword for a font file extension. */
    fun cssFormat(extension: String): String = when (extension.lowercase()) {
        "woff2" -> "woff2"
        "woff" -> "woff"
        "otf" -> "opentype"
        else -> "truetype"
    }

    /**
     * The script that installs [css] into a page and every same-origin frame inside it.
     *
     * Idempotent by construction — it replaces the contents of one known element rather than
     * appending — because the readers it runs in are single-page apps that rebuild their own DOM as
     * you turn pages, so this is re-run on a timer rather than once. Every frame is tried and every
     * failure swallowed: a cross-origin frame throws on access, which is not an error here, it is
     * simply a frame that cannot be styled.
     */
    fun installScript(css: String): String = """
        (function(){
          var css = ${jsString(css)};
          function apply(doc){
            try {
              if (!doc || !doc.head) return;
              var el = doc.getElementById(${jsString(STYLE_ID)});
              if (!el) {
                el = doc.createElement('style');
                el.id = ${jsString(STYLE_ID)};
                doc.head.appendChild(el);
              }
              if (el.textContent !== css) el.textContent = css;
              var frames = doc.querySelectorAll('iframe, frame');
              for (var i = 0; i < frames.length; i++) {
                try { apply(frames[i].contentDocument); } catch (e) {}
              }
            } catch (e) {}
          }
          apply(document);
        })();
    """.trimIndent()

    /** The script that takes the stylesheet back out, for when the reader turns this off. */
    fun removeScript(): String = """
        (function(){
          function strip(doc){
            try {
              if (!doc) return;
              var el = doc.getElementById(${jsString(STYLE_ID)});
              if (el && el.parentNode) el.parentNode.removeChild(el);
              var frames = doc.querySelectorAll('iframe, frame');
              for (var i = 0; i < frames.length; i++) {
                try { strip(frames[i].contentDocument); } catch (e) {}
              }
            } catch (e) {}
          }
          strip(document);
        })();
    """.trimIndent()

    /**
     * A JavaScript double-quoted string literal for [value].
     *
     * The stylesheet is built from settings and from a font path the reader chose, so it is not
     * hostile input — but it is *unbounded* input being spliced into a program, and the difference
     * between those two is a habit rather than a judgement call. Line terminators are escaped
     * (including U+2028/9, which end a line in JavaScript and nowhere else, and would otherwise turn
     * a font name into a syntax error).
     */
    fun jsString(value: String): String {
        val out = StringBuilder("\"")
        value.forEach { c ->
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\u2028' -> out.append("\\u2028")
                '\u2029' -> out.append("\\u2029")
                '<' -> out.append("\\u003C")
                else -> if (c < ' ') out.append("\\u%04X".format(c.code)) else out.append(c)
            }
        }
        return out.append("\"").toString()
    }

    private fun family(settings: ReaderSettings, fontFace: String?): String? = when (settings.typeface) {
        ReaderTypeface.SERIF -> "serif"
        ReaderTypeface.SANS -> "sans-serif"
        ReaderTypeface.MONO -> "monospace"
        // Named first with the platform's sans behind it, so a font that failed to embed degrades to
        // a readable page rather than to whatever the site happened to ask for.
        ReaderTypeface.CUSTOM -> if (fontFace.isNullOrBlank()) null else "'$FONT_FAMILY', sans-serif"
    }

    private fun hex(argb: Int): String = ReaderPalette.hex(argb)

    /** Trim a float to two decimals without a trailing `.0`, so the CSS reads like CSS. */
    private fun trim(value: Float): String {
        val rounded = (value * 100f).roundToInt() / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }

    private const val DEFAULT_FONT_SIZE = 18f
    private const val MIN_ZOOM = 55
    private const val MAX_ZOOM = 200
}
