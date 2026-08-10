package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test
    fun empty_identity_has_no_context_lines() {
        assertTrue(Identity.EMPTY.isEmpty)
        assertTrue(Identity.EMPTY.toContextLines().isEmpty())
    }

    @Test
    fun renders_only_filled_fields() {
        val identity = Identity(
            name = "Sam",
            pronouns = "they/them",
            roles = listOf("engineer", "parent"),
            goals = listOf("ship v2"),
            focus = "the Advisor app",
            traits = mapOf("timezone" to "UTC-7", "empty" to "")
        )
        val lines = identity.toContextLines()

        assertFalse(identity.isEmpty)
        assertTrue(lines.any { it == "Name: Sam (pronouns: they/them)" })
        assertTrue(lines.any { it == "Roles: engineer, parent" })
        assertTrue(lines.any { it == "Goals: ship v2" })
        assertTrue(lines.any { it == "Current focus: the Advisor app" })
        assertTrue(lines.any { it == "timezone: UTC-7" })
        // Blank fields (values, communicationStyle, bio) and blank traits contribute nothing.
        assertFalse(lines.any { it.startsWith("Values") })
        assertFalse(lines.any { it.startsWith("empty") })
    }

    @Test
    fun pronouns_without_name_still_render() {
        val lines = Identity(pronouns = "she/her").toContextLines()
        assertEquals(listOf("Pronouns: she/her"), lines)
    }
}
