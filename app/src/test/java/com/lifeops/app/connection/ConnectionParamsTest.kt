package com.lifeops.app.connection

import org.junit.Assert.*
import org.junit.Test

class ConnectionParamsTest {

    @Test
    fun `getString coerces non-strings and returns null when absent`() {
        val p = ConnectionParams.of("a" to "x", "n" to 5)
        assertEquals("x", p.getString("a"))
        assertEquals("5", p.getString("n"))
        assertNull(p.getString("missing"))
    }

    @Test
    fun `requireString throws MissingParamException on absent or blank`() {
        val p = ConnectionParams.of("blank" to "   ")
        assertThrows(MissingParamException::class.java) { p.requireString("missing") }
        assertThrows(MissingParamException::class.java) { p.requireString("blank") }
    }

    @Test
    fun `getInt parses numbers and numeric strings`() {
        val p = ConnectionParams.of("i" to 3, "d" to 4.0, "s" to "7", "bad" to "nope")
        assertEquals(3, p.getInt("i"))
        assertEquals(4, p.getInt("d"))
        assertEquals(7, p.getInt("s"))
        assertNull(p.getInt("bad"))
        assertNull(p.getInt("missing"))
    }

    @Test
    fun `getLong survives values beyond Int range`() {
        val epoch = 1_753_000_000_000L
        val p = ConnectionParams.of("t" to epoch, "s" to epoch.toString())
        assertEquals(epoch, p.getLong("t"))
        assertEquals(epoch, p.getLong("s"))
    }

    @Test
    fun `getBoolean honours default and coerces strings`() {
        val p = ConnectionParams.of("t" to "true", "f" to false)
        assertTrue(p.getBoolean("t"))
        assertFalse(p.getBoolean("f"))
        assertFalse(p.getBoolean("missing"))
        assertTrue(p.getBoolean("missing", default = true))
    }

    @Test
    fun `getStringList tolerates a scalar or a list`() {
        val p = ConnectionParams.of("one" to "solo", "many" to listOf("a", "b"))
        assertEquals(listOf("solo"), p.getStringList("one"))
        assertEquals(listOf("a", "b"), p.getStringList("many"))
        assertEquals(emptyList<String>(), p.getStringList("missing"))
    }

    @Test
    fun `has reports presence of a non-null value`() {
        val p = ConnectionParams.of("present" to "x", "nulled" to null)
        assertTrue(p.has("present"))
        assertFalse(p.has("nulled"))
        assertFalse(p.has("missing"))
    }
}
