package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDirectivesTest {

    @Test
    fun parses_remember_directives_with_slugged_keys() {
        val reply = """
            Sure, noting that.
            @remember(project-a): shipped the v2 build today
            @remember(LLM Persona): the user prefers terse answers
            All set.
        """.trimIndent()

        val appends = ProfileDirectives.parse(reply)
        assertEquals(2, appends.size)
        assertEquals("project-a", appends[0].profileKey)
        assertEquals("shipped the v2 build today", appends[0].text)
        // Free-form profile name is normalised to its addressable key.
        assertEquals("llm-persona", appends[1].profileKey)
        assertEquals("the user prefers terse answers", appends[1].text)
    }

    @Test
    fun ignores_non_directive_text() {
        assertTrue(ProfileDirectives.parse("just a normal answer, no directives").isEmpty())
    }

    @Test
    fun strip_removes_directive_lines_and_tidies_blanks() {
        val reply = "Answer line.\n@remember(user): likes oat milk\n\nClosing line."
        val stripped = ProfileDirectives.strip(reply)
        assertFalse(stripped.contains("@remember"))
        assertTrue(stripped.contains("Answer line."))
        assertTrue(stripped.contains("Closing line."))
    }

    @Test
    fun slug_normalises_names() {
        assertEquals("project-a", ProfileDirectives.slug("Project A"))
        assertEquals("llm-persona", ProfileDirectives.slug("  LLM__Persona  "))
        assertEquals("weirdname", ProfileDirectives.slug("weird!!name"))
    }
}
