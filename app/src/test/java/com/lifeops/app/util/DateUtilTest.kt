package com.lifeops.app.util

import org.junit.Assert.*
import org.junit.Test

class DateUtilTest {

    @Test
    fun `isValidDate accepts well-formed ISO dates`() {
        assertTrue(DateUtil.isValidDate("2026-06-10"))
        assertTrue(DateUtil.isValidDate("2024-02-29")) // 2024 is a leap year
        assertTrue(DateUtil.isValidDate("2000-01-01"))
    }

    @Test
    fun `isValidDate rejects malformed dates`() {
        assertFalse(DateUtil.isValidDate("2026-13-01"))  // invalid month
        assertFalse(DateUtil.isValidDate("2026-00-01"))  // month zero
        assertFalse(DateUtil.isValidDate("2025-02-29"))  // not a leap year
        assertFalse(DateUtil.isValidDate("not-a-date"))
        assertFalse(DateUtil.isValidDate(""))
        assertFalse(DateUtil.isValidDate("2026/06/10"))  // wrong separator
    }

    @Test
    fun `formatDate returns empty string for null`() {
        assertEquals("", DateUtil.formatDate(null))
    }

    @Test
    fun `formatDate returns raw string for unparseable input`() {
        val bad = "not-a-date"
        assertEquals(bad, DateUtil.formatDate(bad))
    }

    @Test
    fun `formatDate formats valid date correctly`() {
        assertEquals("Jun 10", DateUtil.formatDate("2026-06-10"))
    }

    @Test
    fun `isOverdue returns false for null`() {
        assertFalse(DateUtil.isOverdue(null))
    }
}
