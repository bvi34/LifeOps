package com.citation.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reader's own font is called.
 *
 * The file name is a digest and always will be — content-addressing is what stops one face
 * accumulating a copy per pick. Everything here is about the *label* shown in its place.
 */
class ReaderFontsTest {

    @Test
    fun `a name is taken from the file the reader picked, without its extension`() {
        assertEquals("OpenDyslexic-Regular", ReaderFontNames.fromFileName("OpenDyslexic-Regular.otf"))
        assertEquals("Bookerly", ReaderFontNames.fromFileName("/storage/emulated/0/Download/Bookerly.ttf"))
        assertEquals("Bookerly", ReaderFontNames.fromFileName("C:\\Fonts\\Bookerly.ttf"))
    }

    @Test
    fun `a file name with no extension keeps all of itself`() {
        assertEquals("Bookerly", ReaderFontNames.fromFileName("Bookerly"))
    }

    @Test
    fun `a name is tidied to something that fits on a line`() {
        assertEquals("Atkinson Hyperlegible", ReaderFontNames.clean("  Atkinson   Hyperlegible  "))
        assertEquals("Two lines", ReaderFontNames.clean("Two\nlines"))
        assertEquals("No tabs here", ReaderFontNames.clean("No\ttabs\there"))
    }

    @Test
    fun `a name too long to show is cut rather than allowed to run off`() {
        val long = ReaderFontNames.clean("A".repeat(200))
        assertEquals(ReaderFontNames.MAX_LENGTH, long.length)
    }

    @Test
    fun `cutting a long name never leaves it ending in a space`() {
        val name = ReaderFontNames.clean("Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa bbbbb")
        assertEquals(name.trim(), name)
        assertTrue(name.isNotEmpty())
    }

    @Test
    fun `a name that is only whitespace is no name at all`() {
        assertEquals("", ReaderFontNames.clean("   "))
        assertEquals("", ReaderFontNames.clean("\n\t"))
        assertEquals("", ReaderFontNames.fromFileName(".otf"))
        assertEquals("", ReaderFontNames.fromFileName(""))
    }

    @Test
    fun `a font with no name of its own is named after its file rather than left blank`() {
        val shown = ReaderFontNames.of("/data/fonts/a3f9c2b1d0e4f5a6b7c8d9e0.ttf", null)
        assertEquals("Font a3f9c2", shown)
    }

    @Test
    fun `two unnamed fonts do not end up looking like the same font`() {
        val one = ReaderFontNames.of("/data/fonts/a3f9c2b1d0.ttf", null)
        val two = ReaderFontNames.of("/data/fonts/77e10bc4aa.ttf", null)
        assertTrue(one != two)
    }

    @Test
    fun `the name the reader gave it wins, tidied`() {
        assertEquals("My serif", ReaderFontNames.of("/data/fonts/a3f9c2.ttf", "  My   serif "))
    }

    @Test
    fun `a blank stored name falls back rather than showing as an empty row`() {
        assertEquals("Font a3f9c2", ReaderFontNames.of("/data/fonts/a3f9c2.ttf", "   "))
        assertEquals("Font a3f9c2", ReaderFontNames.of("/data/fonts/a3f9c2.ttf", ""))
    }

    @Test
    fun `a font is where it is and called what it is called`() {
        val font = ReaderFont(path = "/data/fonts/a3f9c2.ttf", name = "OpenDyslexic")
        assertEquals("/data/fonts/a3f9c2.ttf", font.path)
        assertEquals("OpenDyslexic", font.name)
    }
}
