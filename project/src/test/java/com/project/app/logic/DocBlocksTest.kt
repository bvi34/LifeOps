package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocBlocksTest {

    private fun parse(text: String) = DocBlocks.parse(text) { "b$it" }

    @Test
    fun `pasted markdown becomes the blocks it looks like`() {
        val blocks = parse(
            """
            # Chapter One
            
            She left before the tide turned.
            
            ## Notes
            - a bullet
            * another bullet
            1. first
            2) second
            - [ ] unticked
            - [x] ticked
            > a quotation
            ---
            """.trimIndent()
        )

        assertEquals(
            listOf(
                BlockType.HEADING1,
                BlockType.PARAGRAPH,
                BlockType.HEADING2,
                BlockType.BULLET,
                BlockType.BULLET,
                BlockType.NUMBERED,
                BlockType.NUMBERED,
                BlockType.TODO,
                BlockType.TODO,
                BlockType.QUOTE,
                BlockType.DIVIDER
            ),
            blocks.map { it.type }
        )
        assertEquals("Chapter One", blocks[0].text)
        assertEquals("She left before the tide turned.", blocks[1].text)
        assertFalse(blocks[7].checked)
        assertTrue(blocks[8].checked)
        assertEquals("a quotation", blocks[9].text)
    }

    @Test
    fun `hard-wrapped prose joins into one paragraph rather than exploding into lines`() {
        val blocks = parse(
            """
            She left before the tide
            turned, and did not look
            back at the harbour.
            
            The next morning it rained.
            """.trimIndent()
        )

        assertEquals(2, blocks.size)
        assertEquals("She left before the tide turned, and did not look back at the harbour.", blocks[0].text)
        assertEquals("The next morning it rained.", blocks[1].text)
    }

    @Test
    fun `a code fence keeps its lines, blank ones included`() {
        val blocks = parse(
            """
            ```
            fun main() {
            
                println("hi")
            }
            ```
            after
            """.trimIndent()
        )

        assertEquals(BlockType.CODE, blocks[0].type)
        assertEquals("fun main() {\n\n    println(\"hi\")\n}", blocks[0].text)
        assertEquals(BlockType.PARAGRAPH, blocks[1].type)
    }

    @Test
    fun `an unterminated fence keeps the code rather than losing it`() {
        val blocks = parse("```\nnever closed\nstill here")

        assertEquals(1, blocks.size)
        assertEquals(BlockType.CODE, blocks[0].type)
        assertEquals("never closed\nstill here", blocks[0].text)
    }

    @Test
    fun `blocks round-trip through markdown`() {
        val source = """
            # Title
            
            A paragraph.
            
            - one
            - two
            
            1. first
            2. second
            
            - [x] done
            - [ ] not done
            
            > quoted
            
            ---
        """.trimIndent()

        val once = parse(source)
        val rendered = DocBlocks.render(once)
        val twice = parse(rendered)

        assertEquals(once.map { it.type to it.text }, twice.map { it.type to it.text })
        assertEquals(once.map { it.checked }, twice.map { it.checked })
    }

    @Test
    fun `numbered runs are renumbered on export, so a deleted item does not leave a gap`() {
        val blocks = listOf(
            DocBlock("1", BlockType.NUMBERED, "first"),
            DocBlock("2", BlockType.NUMBERED, "third"),
            DocBlock("3", BlockType.PARAGRAPH, "break"),
            DocBlock("4", BlockType.NUMBERED, "restarts")
        )

        val rendered = DocBlocks.render(blocks)

        assertTrue(rendered.contains("1. first"))
        assertTrue(rendered.contains("2. third"))
        assertTrue(rendered.contains("1. restarts"))
    }

    @Test
    fun `word count is prose only, so a pasted config cannot inflate it`() {
        val blocks = listOf(
            DocBlock("1", BlockType.HEADING1, "Chapter One"),
            DocBlock("2", BlockType.PARAGRAPH, "She left before the tide turned."),
            DocBlock("3", BlockType.CODE, "val x = listOf(1, 2, 3, 4, 5, 6, 7, 8)"),
            DocBlock("4", BlockType.DIVIDER, "")
        )

        assertEquals(2 + 6, DocBlocks.wordCount(blocks))
    }

    @Test
    fun `word count ignores repeated and surrounding whitespace`() {
        assertEquals(0, DocBlocks.wordCount(""))
        assertEquals(0, DocBlocks.wordCount("   \n\t "))
        assertEquals(3, DocBlocks.wordCount("  one   two\nthree  "))
    }

    @Test
    fun `headings become the document's own contents list`() {
        val blocks = parse("# One\n\ntext\n\n## Two\n\n### Three")

        assertEquals(listOf(1 to "One", 2 to "Two", 3 to "Three"), DocBlocks.headings(blocks).map { it.level to it.text })
    }

    @Test
    fun `todo progress counts only to-dos`() {
        val blocks = parse("- [x] a\n- [ ] b\n- [x] c\n- not a todo")
        assertEquals(2 to 3, DocBlocks.todoProgress(blocks))
    }

    @Test
    fun `preview skips the heading and truncates`() {
        val blocks = parse("# A title\n\n" + "word ".repeat(60))

        val preview = DocBlocks.preview(blocks, maxChars = 20)

        assertFalse(preview.startsWith("A title"))
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length <= 21)
    }

    @Test
    fun `an empty document previews as nothing at all`() {
        assertEquals("", DocBlocks.preview(emptyList()))
        assertEquals("", DocBlocks.preview(parse("---")))
    }
}
