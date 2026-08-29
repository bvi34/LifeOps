package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineTest {

    @Test
    fun `emphasis is read, and its markers do not survive into the reading`() {
        val spans = MarkdownInline.spans("plain **bold** and *italic* and ~~gone~~")

        assertEquals("plain bold and italic and gone", spans.joinToString("") { it.text })
        assertTrue(spans.single { it.text == "bold" }.bold)
        assertTrue(spans.single { it.text == "italic" }.italic)
        assertTrue(spans.single { it.text == "gone" }.strike)
    }

    @Test
    fun `emphasis nests`() {
        val spans = MarkdownInline.spans("**bold with *both* inside**")
        val both = spans.single { it.text == "both" }
        assertTrue(both.bold)
        assertTrue(both.italic)
    }

    @Test
    fun `a code span is literal all the way through`() {
        val spans = MarkdownInline.spans("run `git commit -m \"*not italic*\"` now")
        val code = spans.single { it.code }
        assertEquals("""git commit -m "*not italic*"""", code.text)
        assertFalse(code.italic)
    }

    @Test
    fun `a link keeps its label and remembers where it points`() {
        val spans = MarkdownInline.spans("see [the notes](https://example.com/notes) for more")
        val link = spans.single { it.link != null }
        assertEquals("the notes", link.text)
        assertEquals("https://example.com/notes", link.link)
    }

    @Test
    fun `an image reads as its alt text`() {
        val spans = MarkdownInline.spans("![the map](map.png)")
        assertEquals("the map", spans.single().text)
        assertEquals("map.png", spans.single().link)
    }

    @Test
    fun `unmatched delimiters stay literal rather than swallowing the line`() {
        assertEquals("half a *thought", MarkdownInline.plain("half a *thought"))
        assertEquals("2 * 3 * 4 = 24", MarkdownInline.plain("2 * 3 * 4 = 24"))
        assertEquals("a `backtick and on", MarkdownInline.plain("a `backtick and on"))
    }

    @Test
    fun `underscores inside a word are part of the word`() {
        val spans = MarkdownInline.spans("call snake_case_name twice")
        assertEquals("call snake_case_name twice", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.italic })
    }

    @Test
    fun `an escaped marker is the character it spells`() {
        val spans = MarkdownInline.spans("""a literal \*star\* here""")
        assertEquals("a literal *star* here", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.italic })
    }

    @Test
    fun `text with no markup needs no parse`() {
        assertFalse(MarkdownInline.hasMarkup("She left before the tide turned."))
        assertTrue(MarkdownInline.hasMarkup("She **left** before the tide turned."))
    }

    @Test
    fun `neighbouring runs of the same styling come back as one span`() {
        val spans = MarkdownInline.spans("**one****two**")
        assertEquals(1, spans.size)
        assertEquals("onetwo", spans.single().text)
    }
}
