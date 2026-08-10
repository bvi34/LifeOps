package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    @Test
    fun appended_adds_entry_and_bumps_timestamp() {
        val base = Profile(key = "project-a", name = "Project A")
        val updated = base.appended("shipped v2", ProfileEntry.AUTHOR_ADVISOR, now = 1234L)
        assertEquals(1, updated.entries.size)
        assertEquals("shipped v2", updated.entries.first().text)
        assertEquals(ProfileEntry.AUTHOR_ADVISOR, updated.entries.first().author)
        assertEquals(1234L, updated.updatedAt)
        // Immutable — the original is untouched.
        assertTrue(base.entries.isEmpty())
    }

    @Test
    fun recent_entries_caps_to_the_last_n() {
        val entries = (1..12).map { ProfileEntry("e$it") }
        val profile = Profile(key = "p", name = "P", entries = entries)
        val recent = profile.recentEntries(max = 3)
        assertEquals(listOf("e10", "e11", "e12"), recent.map { it.text })
    }

    @Test
    fun header_line_shows_key_when_it_differs_from_name() {
        val profile = Profile(key = "project-a", name = "Project A", summary = "the v2 effort")
        val header = profile.headerLine()
        assertTrue(header.contains("Project A"))
        assertTrue(header.contains("[project-a]"))
        assertTrue(header.contains("the v2 effort"))
    }

    @Test
    fun context_lines_are_header_then_bulleted_entries() {
        val profile = Profile(
            key = "user",
            name = "User",
            entries = listOf(ProfileEntry("likes oat milk"))
        )
        val lines = profile.toContextLines()
        assertEquals("User", lines.first())
        assertTrue(lines.any { it == "• likes oat milk" })
    }
}
