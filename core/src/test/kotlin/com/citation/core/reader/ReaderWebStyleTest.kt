package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carrying the reader's settings into somebody else's web reader.
 *
 * The interesting assertions here are the ones about restraint: what this deliberately does *not*
 * say, because it is a guest in a page it did not write and can break a licensed reader that a
 * Citation user has no other way into.
 */
class ReaderWebStyleTest {

    // --- Size -------------------------------------------------------------------------------

    @Test
    fun `the default size asks for no zoom at all`() {
        assertEquals(100, ReaderWebStyle.textZoom(ReaderSettings()))
    }

    @Test
    fun `size becomes a zoom relative to the default rather than a pixel count`() {
        assertTrue(ReaderWebStyle.textZoom(ReaderSettings(fontSize = 26f)) > 100)
        assertTrue(ReaderWebStyle.textZoom(ReaderSettings(fontSize = 12f)) < 100)
    }

    @Test
    fun `zoom stays inside what a page can survive`() {
        val huge = ReaderWebStyle.textZoom(ReaderSettings(fontSize = 36f))
        val tiny = ReaderWebStyle.textZoom(ReaderSettings(fontSize = 10f))
        assertTrue(huge in 101..200)
        assertTrue(tiny in 55..99)
    }

    @Test
    fun `the stylesheet never sets a font size, which is what textZoom is for`() {
        val css = ReaderWebStyle.stylesheet(ReaderSettings(fontSize = 30f, theme = ReaderTheme.SEPIA))
        assertFalse("a px size would flatten a reader that sets type in em", css.contains("font-size"))
    }

    // --- Restraint --------------------------------------------------------------------------

    @Test
    fun `typography is never applied to every element on the page`() {
        val css = ReaderWebStyle.stylesheet(ReaderSettings(theme = ReaderTheme.NIGHT))
        assertFalse("restyling every box resizes their toolbar too", css.contains("*"))
        assertFalse(css.contains("div"))
    }

    @Test
    fun `margins are left to the host reader`() {
        val css = ReaderWebStyle.stylesheet(ReaderSettings(marginDp = 60f, theme = ReaderTheme.PAPER))
        assertFalse(css.contains("margin"))
        assertFalse(css.contains("padding"))
    }

    @Test
    fun `the system theme leaves their colours alone`() {
        val css = ReaderWebStyle.stylesheet(ReaderSettings(theme = ReaderTheme.SYSTEM))
        assertFalse("'System' inside someone else's reader means theirs", css.contains("background"))
        assertFalse(css.contains("color:"))
        // Everything that is not a colour still applies.
        assertTrue(css.contains("line-height"))
    }

    // --- Colour -----------------------------------------------------------------------------

    @Test
    fun `a themed page paints the whole surface, not a column inside it`() {
        val css = ReaderWebStyle.stylesheet(ReaderSettings(theme = ReaderTheme.NIGHT))
        assertTrue(css.contains("html, body"))
        assertTrue(css.contains(ReaderPalette.hex(ReaderPalette.NIGHT_BG)))
        assertTrue(css.contains(ReaderPalette.hex(ReaderPalette.NIGHT_FG)))
    }

    @Test
    fun `colours the reader chose reach the web readers too`() {
        val css = ReaderWebStyle.stylesheet(
            ReaderSettings(
                theme = ReaderTheme.CUSTOM,
                customBackground = 0xFF102030.toInt(),
                customText = 0xFFEEDDCC.toInt()
            )
        )
        assertTrue(css.contains("#102030"))
        assertTrue(css.contains("#EEDDCC"))
    }

    @Test
    fun `warmth reaches them as warmed colours rather than an overlay`() {
        val css = ReaderWebStyle.stylesheet(ReaderSettings(theme = ReaderTheme.PAPER, warmth = 1f))
        assertFalse(css.contains(ReaderPalette.hex(ReaderPalette.PAPER_BG)))
        assertTrue(css.contains(ReaderPalette.hex(ReaderPalette.warm(ReaderPalette.PAPER_BG, 1f))))
    }

    // --- Setting ----------------------------------------------------------------------------

    @Test
    fun `justification and hyphenation carry across, prefixed for the WebView`() {
        val on = ReaderWebStyle.stylesheet(ReaderSettings(justify = true, hyphenate = true))
        assertTrue(on.contains("text-align: justify"))
        assertTrue(on.contains("-webkit-hyphens: auto"))
        assertTrue(on.contains("hyphens: auto"))

        val off = ReaderWebStyle.stylesheet(ReaderSettings(justify = false, hyphenate = false))
        assertTrue(off.contains("text-align: start"))
        assertTrue(off.contains("hyphens: manual"))
    }

    @Test
    fun `line spacing is written as a number CSS will read`() {
        assertTrue(ReaderWebStyle.stylesheet(ReaderSettings(lineSpacing = 1.6f)).contains("line-height: 1.6"))
        assertTrue(ReaderWebStyle.stylesheet(ReaderSettings(lineSpacing = 2.0f)).contains("line-height: 2"))
    }

    @Test
    fun `tracking is only mentioned when it was asked for`() {
        assertFalse(ReaderWebStyle.stylesheet(ReaderSettings(letterSpacing = 0f)).contains("letter-spacing"))
        assertTrue(ReaderWebStyle.stylesheet(ReaderSettings(letterSpacing = 0.1f)).contains("letter-spacing: 0.1em"))
    }

    // --- Fonts ------------------------------------------------------------------------------

    @Test
    fun `the built-in faces map to families the WebView already has`() {
        assertTrue(ReaderWebStyle.stylesheet(ReaderSettings(typeface = ReaderTypeface.SERIF)).contains("font-family: serif"))
        assertTrue(ReaderWebStyle.stylesheet(ReaderSettings(typeface = ReaderTypeface.SANS)).contains("font-family: sans-serif"))
        assertTrue(ReaderWebStyle.stylesheet(ReaderSettings(typeface = ReaderTypeface.MONO)).contains("font-family: monospace"))
    }

    @Test
    fun `a reader's own font is asked for only once it has actually been embedded`() {
        val settings = ReaderSettings(typeface = ReaderTypeface.CUSTOM, customFontPath = "/data/f.ttf")
        assertFalse(
            "naming a family that was never installed would silently pick the site's font",
            ReaderWebStyle.stylesheet(settings).contains("font-family")
        )
        val embedded = ReaderWebStyle.stylesheet(settings, ReaderWebStyle.fontFaceRule("QUJD", "ttf"))
        assertTrue(embedded.contains("@font-face"))
        assertTrue(embedded.contains(ReaderWebStyle.FONT_FAMILY))
        assertTrue("a font that fails to load must still leave a readable page", embedded.contains("sans-serif"))
    }

    @Test
    fun `a font is embedded as data because an https page cannot reach the filesystem`() {
        val rule = ReaderWebStyle.fontFaceRule("QUJD", "otf")
        assertTrue(rule.contains("url(data:font/otf;base64,QUJD)"))
        assertTrue(rule.contains("format('opentype')"))
    }

    @Test
    fun `font formats map to the keywords CSS knows`() {
        assertEquals("truetype", ReaderWebStyle.cssFormat("TTF"))
        assertEquals("opentype", ReaderWebStyle.cssFormat("otf"))
        assertEquals("woff2", ReaderWebStyle.cssFormat("woff2"))
        assertEquals("truetype", ReaderWebStyle.cssFormat("mystery"))
    }

    // --- Injection --------------------------------------------------------------------------

    @Test
    fun `the script replaces one known element rather than appending another`() {
        val script = ReaderWebStyle.installScript("body{color:red}")
        assertTrue(script.contains(ReaderWebStyle.STYLE_ID))
        assertTrue("re-run on a timer, so it must not stack", script.contains("getElementById"))
        assertTrue(script.contains("el.textContent !== css"))
    }

    @Test
    fun `every frame is tried and every failure swallowed`() {
        val script = ReaderWebStyle.installScript("body{}")
        // A cross-origin frame throws on access. That is a frame that cannot be styled, not an error.
        assertTrue(script.contains("iframe"))
        assertTrue(script.contains("catch"))
    }

    @Test
    fun `turning it off takes the stylesheet back out of every frame`() {
        val script = ReaderWebStyle.removeScript()
        assertTrue(script.contains("removeChild"))
        assertTrue(script.contains(ReaderWebStyle.STYLE_ID))
        assertTrue(script.contains("iframe"))
    }

    // --- Splicing a stylesheet into a program ------------------------------------------------

    @Test
    fun `a quote or a backslash cannot end the string it is being put in`() {
        assertEquals("\"a\\\"b\"", ReaderWebStyle.jsString("a\"b"))
        assertEquals("\"a\\\\b\"", ReaderWebStyle.jsString("a\\b"))
    }

    @Test
    fun `a newline cannot end the statement it is being put in`() {
        assertEquals("\"a\\nb\"", ReaderWebStyle.jsString("a\nb"))
        assertEquals("\"a\\rb\"", ReaderWebStyle.jsString("a\rb"))
    }

    @Test
    fun `the separators that end a line in JavaScript and nowhere else are escaped`() {
        assertEquals("\"a\\u2028b\"", ReaderWebStyle.jsString("a b"))
        assertEquals("\"a\\u2029b\"", ReaderWebStyle.jsString("a b"))
    }

    @Test
    fun `a multi-line stylesheet survives being spliced into a script`() {
        val script = ReaderWebStyle.installScript(ReaderWebStyle.stylesheet(ReaderSettings(theme = ReaderTheme.SEPIA)))
        val body = script.substringAfter("var css = ").substringBefore(";\n")
        assertTrue("the literal must stay on one line", !body.contains("\n"))
        assertEquals(2, body.count { it == '"' })
    }

    @Test
    fun `an ordinary stylesheet is left readable`() {
        assertEquals("\"body{color:red}\"", ReaderWebStyle.jsString("body{color:red}"))
    }
}
