package com.citation.core.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reader actually gets to draw: the blocks and inline spans read off the reduction.
 *
 * Every assertion here is expressed as *text recovered through a range*, never as a raw offset —
 * that is the contract the renderer relies on and the one that keeps notes anchored.
 */
class HtmlStructureTest {

    private fun parse(html: String) = HtmlDocument.parse(html)

    private fun HtmlDocument.Parsed.textOf(block: DocumentBlock): String =
        text.substring(block.start, block.end)

    private fun HtmlDocument.Parsed.textOf(span: InlineSpan): String =
        text.substring(span.start, span.end)

    private fun HtmlDocument.Parsed.texts(): List<DocumentBlock.Text> =
        blocks.filterIsInstance<DocumentBlock.Text>()

    @Test
    fun `headings carry their level and paragraphs stay paragraphs`() {
        val parsed = parse("<h1>The Title</h1><p>Body text.</p><h3>Sub</h3><p>More.</p>")
        val blocks = parsed.texts()
        assertEquals(4, blocks.size)
        assertEquals(BlockKind.HEADING, blocks[0].kind)
        assertEquals(1, blocks[0].level)
        assertEquals("The Title", parsed.textOf(blocks[0]))
        assertEquals(BlockKind.PARAGRAPH, blocks[1].kind)
        assertEquals("Body text.", parsed.textOf(blocks[1]))
        assertEquals(3, blocks[2].level)
        assertEquals("Sub", parsed.textOf(blocks[2]))
    }

    @Test
    fun `the first heading names the chapter`() {
        assertEquals("Chapter One", parse("<h2>Chapter One</h2><p>x</p>").heading)
        assertNull(parse("<p>no heading here</p>").heading)
    }

    @Test
    fun `inline emphasis is recovered as ranges over the same text`() {
        val parsed = parse("<p>An <em>italic</em> and a <strong>bold</strong> word.</p>")
        val spans = parsed.texts().single().spans
        assertEquals(2, spans.size)
        assertEquals(InlineStyle.ITALIC, spans[0].style)
        assertEquals("italic", parsed.textOf(spans[0]))
        assertEquals(InlineStyle.BOLD, spans[1].style)
        assertEquals("bold", parsed.textOf(spans[1]))
        // The text itself is untouched by any of it.
        assertEquals("An italic and a bold word.", parsed.text)
    }

    @Test
    fun `nested emphasis produces overlapping spans rather than a merged one`() {
        val parsed = parse("<p>a <em>b <strong>c</strong></em> d</p>")
        val spans = parsed.texts().single().spans.sortedBy { it.style.name }
        assertEquals("c", parsed.textOf(spans.first { it.style == InlineStyle.BOLD }))
        assertEquals("b c", parsed.textOf(spans.first { it.style == InlineStyle.ITALIC }))
    }

    @Test
    fun `block quotes and verse are distinguished from body text`() {
        val quote = parse("<blockquote><p>Quoted words.</p></blockquote>")
        assertEquals(BlockKind.BLOCKQUOTE, quote.texts().single().kind)

        val verse = parse("<p class=\"verse\">First line<br>Second line</p>")
        val block = verse.texts().single()
        assertEquals(BlockKind.VERSE, block.kind)
        // Verse keeps its single newline inside one block, so the renderer can honour the break.
        assertEquals("First line\nSecond line", verse.textOf(block))
    }

    @Test
    fun `code blocks are marked so they are never reflowed`() {
        val parsed = parse("<pre>fun main() {\n    println(1)\n}</pre>")
        assertEquals(BlockKind.CODE, parsed.texts().single().kind)
    }

    @Test
    fun `list items know their marker and their number`() {
        val parsed = parse("<ol><li>alpha</li><li>beta</li></ol><ul><li>bullet</li></ul>")
        val items = parsed.texts()
        assertEquals(3, items.size)
        assertTrue(items.all { it.kind == BlockKind.LIST_ITEM })
        assertTrue(items[0].ordered)
        assertEquals(1, items[0].itemIndex)
        assertEquals(2, items[1].itemIndex)
        assertEquals(false, items[2].ordered)
    }

    @Test
    fun `images are zero-width markers so they cannot move an anchor`() {
        val parsed = parse("<p>Before.</p><img src=\"plate.jpg\" alt=\"A plate\"/><p>After.</p>")
        val image = parsed.blocks.filterIsInstance<DocumentBlock.Image>().single()
        assertEquals("plate.jpg", image.src)
        assertEquals("A plate", image.alt)
        assertEquals(image.start, image.end)
        assertTrue(image.isMarker)
        // The text is exactly what it would have been with no image support at all.
        assertEquals("Before.\n\nAfter.", parsed.text)
    }

    @Test
    fun `an svg cover image is found through its xlink href`() {
        val parsed = parse("<svg><image xlink:href=\"cover.jpeg\"/></svg>")
        assertEquals("cover.jpeg", parsed.blocks.filterIsInstance<DocumentBlock.Image>().single().src)
    }

    @Test
    fun `a scene break becomes a rule`() {
        val parsed = parse("<p>a</p><hr/><p>b</p>")
        assertEquals(1, parsed.blocks.filterIsInstance<DocumentBlock.Rule>().size)
        assertEquals("a\n\nb", parsed.text)
    }

    @Test
    fun `tables keep a grid over text the reduction ran together`() {
        val parsed = parse(
            "<table><tr><th>Name</th><th>Year</th></tr><tr><td>Ada</td><td>1843</td></tr></table>"
        )
        val table = parsed.blocks.filterIsInstance<DocumentBlock.Table>().single()
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0].header)
        assertEquals(listOf("Name", "Year"), table.rows[0].cells.map { parsed.text.substring(it) })
        assertEquals(listOf("Ada", "1843"), table.rows[1].cells.map { parsed.text.substring(it) })
        // The canonical text still runs the cells together, exactly as it always has.
        assertEquals("NameYear\n\nAda1843", parsed.text)
    }

    @Test
    fun `footnote references are separated from ordinary links`() {
        val parsed = parse(
            "<p>Claim<sup><a href=\"#fn1\" epub:type=\"noteref\">1</a></sup> and " +
                "<a href=\"https://example.com\">a link</a>.</p>"
        )
        val spans = parsed.texts().single().spans
        val note = spans.single { it.style == InlineStyle.FOOTNOTE_REF }
        assertEquals("#fn1", note.href)
        assertEquals("1", parsed.textOf(note))
        val link = spans.single { it.style == InlineStyle.LINK }
        assertEquals("https://example.com", link.href)
    }

    @Test
    fun `an anchor with no destination is a link target, not a link`() {
        // Converted books scatter `<a id="page17">` through their prose as page markers, and some
        // leave one wrapped around a whole paragraph. Styled as a link, that came out as a chapter
        // of underlined, accent-coloured prose.
        val parsed = parse("<p><a id=\"page17\">A whole paragraph of ordinary prose.</a></p>")
        assertTrue(parsed.texts().single().spans.none { it.style == InlineStyle.LINK })
        assertTrue(parsed.text.contains("A whole paragraph of ordinary prose."))
    }

    @Test
    fun `an anchor named but not linked is still indexed as a destination`() {
        // Nothing is styled, but the id must still be reachable — that is what the anchor is for.
        val parsed = parse("<p>One.</p><p><a id=\"page17\"></a>Page seventeen begins.</p>")
        assertNotNull(parsed.anchors["page17"])
    }

    @Test
    fun `an empty href earns no styling either`() {
        val parsed = parse("<p>Text <a href=\"\">not really a link</a>.</p>")
        assertTrue(parsed.texts().single().spans.none { it.style == InlineStyle.LINK })
    }

    @Test
    fun `a superscripted internal link counts as a note reference without any markup hint`() {
        val parsed = parse("<p>Claim<sup><a href=\"#n7\">7</a></sup>.</p>")
        assertTrue(parsed.texts().single().spans.any { it.style == InlineStyle.FOOTNOTE_REF })
    }

    @Test
    fun `element ids are indexed so a link can land on a place in the text`() {
        val parsed = parse("<p>One.</p><p id=\"fn1\">The note itself.</p>")
        val offset = parsed.anchors["fn1"]
        assertNotNull(offset)
        assertTrue(parsed.text.substring(offset!!).startsWith("The note itself."))
    }

    @Test
    fun `an href with an escaped ampersand is decoded`() {
        val parsed = parse("<p><a href=\"http://x.test/?a=1&amp;b=2\">q</a></p>")
        assertEquals("http://x.test/?a=1&b=2", parsed.texts().single().spans.single().href)
    }

    @Test
    fun `unclosed markup degrades to blocks rather than throwing`() {
        // Nothing closes, so the reduction never emits a break — one run, as it always has. The
        // point is that the walker survives the dangling opens and still classifies what it has.
        val parsed = parse("<div><p>one<p>two<blockquote>three")
        // Nothing closes, so the reduction emits no breaks and the text runs together — as it
        // always has. Structure still separates the three, because each element's *open* is a
        // block boundary even when its close never arrives.
        assertEquals("onetwothree", parsed.text)
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.BLOCKQUOTE),
            parsed.texts().map { it.kind }
        )
        assertEquals(listOf("one", "two", "three"), parsed.texts().map { parsed.textOf(it) })
    }

    @Test
    fun `a stray closing tag with no open is ignored`() {
        val parsed = parse("</em><p>text</p></div>")
        assertEquals("text", parsed.text)
        assertEquals(BlockKind.PARAGRAPH, parsed.texts().single().kind)
    }

    @Test
    fun `blocks tile the text without gaps or overlaps`() {
        val parsed = parse(
            "<h1>T</h1><p>a</p><blockquote><p>b</p></blockquote><ul><li>c</li></ul><p>d</p>"
        )
        val ranged = parsed.blocks.filter { !it.isMarker }
        var cursor = 0
        ranged.forEach { block ->
            assertTrue("block starts before the previous ended", block.start >= cursor)
            cursor = block.end
        }
        assertEquals(parsed.text.length, cursor)
    }
}
