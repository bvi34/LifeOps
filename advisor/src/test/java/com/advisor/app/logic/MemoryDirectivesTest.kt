package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDirectivesTest {

    @Test
    fun parses_memorize_directives_with_content_and_tags() {
        val reply = """
            Noted.
            @memorize: the user's dog is named Rex #pets #family
            @memorize: prefers meetings after 10am
            Done.
        """.trimIndent()

        val writes = MemoryDirectives.parse(reply)
        assertEquals(2, writes.size)
        assertEquals("the user's dog is named Rex", writes[0].content)
        assertEquals(listOf("pets", "family"), writes[0].tags)
        // A directive with no hashtags still parses — just tagless.
        assertEquals("prefers meetings after 10am", writes[1].content)
        assertTrue(writes[1].tags.isEmpty())
    }

    @Test
    fun tags_are_lowercased_and_deduped() {
        val writes = MemoryDirectives.parse("@memorize: quarterly review #Work #work #Q3")
        assertEquals(1, writes.size)
        assertEquals("quarterly review", writes[0].content)
        assertEquals(listOf("work", "q3"), writes[0].tags)
    }

    @Test
    fun ignores_non_directive_text() {
        assertTrue(MemoryDirectives.parse("just a normal answer, nothing to save").isEmpty())
    }

    @Test
    fun strip_removes_directive_lines_and_tidies_blanks() {
        val reply = "Answer line.\n@memorize: likes oat milk #food\n\nClosing line."
        val stripped = MemoryDirectives.strip(reply)
        assertFalse(stripped.contains("@memorize"))
        assertTrue(stripped.contains("Answer line."))
        assertTrue(stripped.contains("Closing line."))
    }

    @Test
    fun blank_directive_is_dropped() {
        assertTrue(MemoryDirectives.parse("@memorize:   #onlytags").isEmpty())
    }
}
