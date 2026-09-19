package com.operations.vaultkit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CSV reader, tested on the cells that break the afternoon version of it.
 *
 * Splitting on commas works for the first hundred rows of anybody's export and then meets a note
 * with a comma in it. Every test here is one of those rows.
 */
class CsvTest {

    @Test
    fun `a quoted cell keeps its commas`() {
        val rows = Csv.parse("name,note\nBank,\"one, two, three\"\n")

        assertEquals(listOf("Bank", "one, two, three"), rows[1])
    }

    @Test
    fun `a quoted cell keeps its newlines`() {
        val rows = Csv.parse("name,note\nBank,\"line one\nline two\"\nShop,x\n")

        assertEquals(3, rows.size)
        assertEquals("line one\nline two", rows[1][1])
        assertEquals("Shop", rows[2][0])
    }

    @Test
    fun `a doubled quote is one quote`() {
        val rows = Csv.parse("name\n\"she said \"\"hello\"\"\"\n")

        assertEquals("she said \"hello\"", rows[1][0])
    }

    @Test
    fun `carriage returns are line endings, alone or in a pair`() {
        assertEquals(3, Csv.parse("a,b\r\n1,2\r\n3,4\r\n").size)
        assertEquals(3, Csv.parse("a,b\r1,2\r3,4\r").size)
    }

    @Test
    fun `a byte order mark does not become part of the first header`() {
        val rows = Csv.parse("﻿url,username\nhttps://bank.example,me\n")

        assertEquals("url", rows[0][0])
    }

    @Test
    fun `a semicolon file is read as a semicolon file`() {
        val rows = Csv.parse("name;url;password\nBank;bank.example;hunter2\n")

        assertEquals(listOf("Bank", "bank.example", "hunter2"), rows[1])
    }

    @Test
    fun `a note full of semicolons does not outvote the real delimiter`() {
        val rows = Csv.parse("name,note\nBank,\"a;b;c;d;e;f\"\n")

        assertEquals(2, rows[1].size)
        assertEquals("a;b;c;d;e;f", rows[1][1])
    }

    @Test
    fun `blank lines are not rows`() {
        val rows = Csv.parse("a,b\n\n1,2\n\n")

        assertEquals(2, rows.size)
    }

    @Test
    fun `an empty trailing cell is still a cell`() {
        val rows = Csv.parse("a,b,c\n1,2,\n")

        assertEquals(3, rows[1].size)
        assertEquals("", rows[1][2])
    }

    @Test
    fun `a quote inside an unquoted cell is left where it is`() {
        val rows = Csv.parse("name\n6\" ruler\n")

        assertEquals("6\" ruler", rows[1][0])
    }
}
