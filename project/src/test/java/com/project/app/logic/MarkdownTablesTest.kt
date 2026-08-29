package com.project.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTablesTest {

    private val table = """
        | Stage | Type | Speed |
        |---|:---:|---:|
        | 1 | Normal | Slow |
        | 3 | Speed | Very Fast |
    """.trimIndent()

    @Test
    fun `a pipe table is read as its cells`() {
        val parsed = MarkdownTables.parse(table)!!

        assertEquals(listOf("Stage", "Type", "Speed"), parsed.header)
        assertEquals(2, parsed.rows.size)
        assertEquals(listOf("3", "Speed", "Very Fast"), parsed.rows[1])
    }

    @Test
    fun `the delimiter row's colons are the columns' alignment`() {
        val parsed = MarkdownTables.parse(table)!!

        assertEquals(ColumnAlign.LEFT, parsed.alignmentOf(0))
        assertEquals(ColumnAlign.CENTER, parsed.alignmentOf(1))
        assertEquals(ColumnAlign.RIGHT, parsed.alignmentOf(2))
    }

    @Test
    fun `a ragged table is squared off rather than left with holes`() {
        val parsed = MarkdownTables.parse(
            """
            | A | B | C |
            |---|---|---|
            | 1 |
            | 1 | 2 | 3 | 4 |
            """.trimIndent()
        )!!

        assertEquals(4, parsed.columns)
        assertEquals(listOf("1", "", "", ""), parsed.rows[0])
        assertEquals(listOf("A", "B", "C", ""), parsed.header)
    }

    @Test
    fun `rendering a parsed table gives back the same table`() {
        val once = MarkdownTables.parse(table)!!.render()
        val twice = MarkdownTables.parse(once)!!.render()

        assertEquals(once, twice)
        assertEquals(MarkdownTables.parse(once)!!.rows, MarkdownTables.parse(table)!!.rows)
        assertTrue(once.lines().all { it.startsWith("|") && it.endsWith("|") })
    }

    @Test
    fun `alignment survives the round trip`() {
        val parsed = MarkdownTables.parse(MarkdownTables.parse(table)!!.render())!!
        assertEquals(ColumnAlign.CENTER, parsed.alignmentOf(1))
        assertEquals(ColumnAlign.RIGHT, parsed.alignmentOf(2))
    }

    @Test
    fun `a pipe inside a cell is not a column boundary`() {
        val parsed = MarkdownTables.parse(
            """
            | Key | Value |
            |---|---|
            | pipe | a \| b |
            """.trimIndent()
        )!!

        assertEquals(2, parsed.columns)
        assertEquals("a \\| b", parsed.rows[0][1])
    }

    @Test
    fun `text that is not a table is not read as one`() {
        assertNull(MarkdownTables.parse("She left before the tide turned."))
        assertNull(MarkdownTables.parse("| just | one | row |"))
        assertNull(MarkdownTables.coerce("nothing tabular here at all"))
    }

    @Test
    fun `a table flattened into one line is put back together`() {
        // What a table looked like in a document written before tables were blocks: the newlines
        // are gone, the pipes are not.
        val flattened =
            "| Stage | Type | Health | Special | |---|---|---|---| | 1 | Normal | Low | None | " +
                "| 3 | Speed | Medium | Vault | | 4 | Tank | High | Throws Objects |"

        val parsed = MarkdownTables.recover(flattened)!!

        assertEquals(listOf("Stage", "Type", "Health", "Special"), parsed.header)
        assertEquals(3, parsed.rows.size)
        assertEquals(listOf("1", "Normal", "Low", "None"), parsed.rows[0])
        assertEquals(listOf("4", "Tank", "High", "Throws Objects"), parsed.rows[2])
    }

    @Test
    fun `an empty cell in a flattened table keeps its column`() {
        val flattened = "| A | B | C | |---|---|---| | 1 | | 3 | | | 5 | 6 |"

        val parsed = MarkdownTables.recover(flattened)!!

        assertEquals(listOf("1", "", "3"), parsed.rows[0])
        assertEquals(listOf("", "5", "6"), parsed.rows[1])
    }

    @Test
    fun `coerce takes a table however it survived`() {
        assertEquals(
            MarkdownTables.parse(table)!!.rows,
            MarkdownTables.coerce(MarkdownTables.parse(table)!!.render())!!.rows
        )
        assertEquals(
            listOf("1", "Normal"),
            MarkdownTables.coerce("| Stage | Type | |---|---| | 1 | Normal |")!!.rows[0]
        )
    }

    @Test
    fun `a blank table is a table`() {
        val blank = MarkdownTables.parse(MarkdownTables.blank(columns = 3, rows = 2))!!
        assertEquals(3, blank.columns)
        assertEquals(2, blank.rows.size)
        assertTrue(blank.rows.flatten().all { it.isEmpty() })
    }

    @Test
    fun `a delimiter row is recognised, and a divider is not`() {
        assertTrue(MarkdownTables.isDelimiterRow("|---|:--:|--:|"))
        assertTrue(MarkdownTables.isDelimiterRow("--- | ---"))
        assertTrue(MarkdownTables.startsAt(listOf("| a | b |", "|---|---|"), 0))
        assertTrue(!MarkdownTables.startsAt(listOf("| a | b |", "---"), 0))
        assertTrue(!MarkdownTables.startsAt(listOf("some prose", "more prose"), 0))
    }
    @Test
    fun `a miscounted delimiter row does not shunt every cell one column left`() {
        // Eight columns of header, seven groups of dashes under them — the mistake a hand-written
        // table makes, and the one that used to shift a whole stat block sideways.
        val flattened =
            "| Stage | Type | Health | Damage | Speed | Infection | Special | Unique | " +
                "|---:|---|---|---|---|---|---| " +
                "| 1 | Normal | Low | Medium | Slow-Normal | Small | None | Civilian | " +
                "| 3 | Speed (Var) | Medium | Medium | Fast-Very Fast | Medium | Vault | I Am Legend | " +
                "| 4 | Tank | High | High | Medium | Medium | Throws Objects | Tank (L4D) |"

        val parsed = MarkdownTables.recover(flattened)!!

        assertEquals(8, parsed.columns)
        assertEquals("Unique", parsed.header.last())
        assertEquals(3, parsed.rows.size)
        assertEquals(
            listOf("1", "Normal", "Low", "Medium", "Slow-Normal", "Small", "None", "Civilian"),
            parsed.rows[0]
        )
        assertEquals("Tank (L4D)", parsed.rows[2].last())
    }

    @Test
    fun `a row with an empty cell still lines up with the rows around it`() {
        val flattened =
            "| Stage | Type | Special | |---|---|---| " +
                "| 4 | Seekers | Give Orders to Zombies | " +
                "| 4 | Plague Carrier | | " +
                "| 4 | Mimicker | Makes Human Noises |"

        val parsed = MarkdownTables.recover(flattened)!!

        assertEquals(3, parsed.rows.size)
        assertEquals(listOf("4", "Plague Carrier", ""), parsed.rows[1])
        assertEquals(listOf("4", "Mimicker", "Makes Human Noises"), parsed.rows[2])
    }
}
