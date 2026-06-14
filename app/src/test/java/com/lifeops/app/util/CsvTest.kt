package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class CsvTest {

    @Test
    fun `quotes only when needed`() {
        assertEquals("plain", Csv.field("plain"))
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"line\nbreak\"", Csv.field("line\nbreak"))
    }

    @Test
    fun `doubles embedded quotes`() {
        assertEquals("\"he said \"\"hi\"\"\"", Csv.field("he said \"hi\""))
    }

    @Test
    fun `row and parseLine round-trip through commas and quotes`() {
        val fields = listOf("id-1", "Title, with comma", "He said \"hi\"", "")
        assertEquals(fields, Csv.parseLine(Csv.row(fields)))
    }

    @Test
    fun `parseLine keeps a trailing empty field`() {
        assertEquals(listOf("a", "b", ""), Csv.parseLine("a,b,"))
    }
}
