package com.maintenance.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `cents are written the way a statement writes them`() {
        assertEquals("$1,234.56", Money.format(123456))
        assertEquals("$0.07", Money.format(7))
        assertEquals("-$45.00", Money.format(-4500))
        assertEquals("$1,234", Money.format(123456, cents = false))
        assertEquals("£12.00", Money.format(1200, symbol = "£"))
    }

    @Test
    fun `what people paste out of a bank app parses`() {
        assertEquals(123456L, Money.parse("$1,234.56"))
        assertEquals(123456L, Money.parse(" 1234.56 "))
        assertEquals(120000L, Money.parse("1200"))
        assertEquals(1250L, Money.parse("12.5"))
        assertEquals(-4500L, Money.parse("-45"))
    }

    @Test
    fun `a third decimal is a typo, not a fraction of a cent`() {
        assertNull(Money.parse("12.345"))
        assertNull(Money.parse("twelve"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("1.2.3"))
    }

    @Test
    fun `format and parse round trip`() {
        listOf(0L, 5L, 999L, 123456L, 98765432L).forEach { cents ->
            assertEquals(cents, Money.parse(Money.format(cents)))
        }
    }
}
