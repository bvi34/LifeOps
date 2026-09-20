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
 *  - **The page's measure is deliberately absent.** The margin setting is the one most likely to
 *    break a fixed-layout reader and the least missed inside one that already manages its own
 *    column width. The space *between paragraphs* is a different setting — it is how the reader
 *    asked prose to be set, not how wide the page is — and is carried; see [stylesheet].
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

    /** The headings within [PROSE], for the reader's own heading colour. */
    private const val HEADINGS = "h1, h2, h3, h4, h5, h6"

    /**
     * Running prose, and only that.
     *
     * Paragraph setting is the one part of the typography that cannot go to [PROSE]: a first-line
     * indent on a list item puts the bullet's text out of line with itself, and on a table cell it
     * is simply wrong. A `p` is the only element on anybody's page that is reliably a paragraph.
     */
    private const val PARAGRAPHS = "p"

    /** A paragraph opening a section, which carries no mark — see [paragraphRules]. */
    private const val FIRST_PARAGRAPHS =
        "h1 + p, h2 + p, h3 + p, h4 + p, h5 + p, h6 + p, hr + p, p:first-child"

    /**
     * Links, in every state. `:visited` is named explicitly because a browser's own visited colour
     * is not inherited from `a` and would otherwise be the one thing on the page still set in
     * somebody else's palette.
     */
    private const val LINKS = "a, a:link, a:visited, a:hover"

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
        ReaderPalette.colors(settings)?.let { colours ->
            val bg = hex(colours.page)
            val fg = hex(colours.text)
            rules.append("html, body { background: $bg !important; color: $fg !important; }\n")
            rules.append("$PROSE { color: $fg !important; }\n")
            // Headings and links are stated after the prose rule, so they win on the elements they
            // name — and stated at all because a host reader paints both in *its* colours, which is
            // how a reader who set a night page ends up with sky-blue links on it.
            if (colours.heading != colours.text) {
                rules.append("$HEADINGS { color: ${hex(colours.heading)} !important; }\n")
            }
            val link = hex(colours.link)
            rules.append("$LINKS { color: $link !important; }\n")
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
        rules.append(paragraphRules(settings))

        return rules.toString()
    }

    /**
     * How one paragraph is separated from the next, in somebody else's reader.
     *
     * Here because a setting that reaches one of four reading tracks is barely a setting: a reader
     * who has decided their paragraphs are indented, spaced or both has decided it about *reading*,
     * not about EPUBs. The host's own answer is overridden in both directions — the indent is set to
     * zero when it is not wanted, and the space between paragraphs likewise — because leaving half
     * of it alone means the reader's choice shows up on some books and not others, which is worse
     * than not carrying it at all.
     *
     * The space is exactly the blank line Citation's own track draws: one line at the reader's line
     * spacing, so a book read in one track and then the other is set the same way.
     *
     * Restricted to `p` (see [PARAGRAPHS]), and the paragraph opening a section is excused the
     * indent as it is on Citation's own page — the convention every printed book follows, and the
     * reason an indent does not read as a mistake.
     */
    private fun paragraphRules(settings: ReaderSettings): String {
        val indent = if (settings.paragraphs.indents) "$PARAGRAPH_INDENT_EM" + "em" else "0"
        val gap = if (settings.paragraphs.spaces) "${trim(settings.lineSpacing)}em" else "0"
        val rules = StringBuilder()
        rules.append(
            "$PARAGRAPHS { text-indent: $indent !important; " +
                "margin-top: $gap !important; margin-bottom: $gap !important; }\n"
        )
        if (settings.paragraphs.indents) {
            rules.append("$FIRST_PARAGRAPHS { text-indent: 0 !important; }\n")
        }
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

    /**
     * The first-line indent, in em — the same measure the EPUB track sets, so the two tracks do not
     * disagree about how deep an indent is.
     */
    private const val PARAGRAPH_INDENT_EM = 1.3f

    private const val DEFAULT_FONT_SIZE = 18f
    private const val MIN_ZOOM = 55
    private const val MAX_ZOOM = 200
}
