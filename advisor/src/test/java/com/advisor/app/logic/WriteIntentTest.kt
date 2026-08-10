package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteIntentTest {

    private val profiles = listOf(
        Profile(key = "user", name = "User", kind = ProfileKind.USER),
        Profile(key = "llm-persona", name = "LLM Persona", kind = ProfileKind.PERSONA)
    )

    private fun detect(q: String) = WriteIntent.detect(q, profiles)

    @Test
    fun add_to_named_profile_is_a_profile_write() {
        // The exact phrasing from the app: it should now actually persist, not ask for clarification.
        val result = detect("add to LLM persona that you are called Ava now")
        assertTrue(result.hasWrites)
        assertTrue(result.memoryWrites.isEmpty())
        assertEquals(1, result.profileAppends.size)
        assertEquals("llm-persona", result.profileAppends[0].profileKey)
        assertEquals("you are called Ava", result.profileAppends[0].text)
    }

    @Test
    fun profile_target_matches_the_key_or_spaced_key() {
        assertEquals("user", detect("save to user that I prefer tea").profileAppends[0].profileKey)
        assertEquals("llm-persona", detect("note in llm-persona: keep answers terse").profileAppends[0].profileKey)
    }

    @Test
    fun remember_that_is_a_memory_write() {
        val result = detect("remember that my dog is named Rex #pets")
        assertTrue(result.hasWrites)
        assertTrue(result.profileAppends.isEmpty())
        assertEquals(1, result.memoryWrites.size)
        assertEquals("my dog is named Rex", result.memoryWrites[0].content)
        assertEquals(listOf("pets"), result.memoryWrites[0].tags)
    }

    @Test
    fun save_to_memory_target_is_a_memory_write() {
        val result = detect("save to memory: buy flour before Sunday")
        assertEquals(1, result.memoryWrites.size)
        assertEquals("buy flour before Sunday", result.memoryWrites[0].content)
    }

    @Test
    fun questions_are_not_write_commands() {
        assertFalse(detect("do you remember what I told you?").hasWrites)
        assertFalse(detect("what is my name?").hasWrites)
        // "remember when …" reads as reminiscing, not an instruction.
        assertFalse(detect("remember when we first met").hasWrites)
    }

    @Test
    fun ordinary_sentences_are_not_writes() {
        // "add milk to the grocery list" names no known profile and no memory target.
        assertFalse(detect("add milk to the grocery list").hasWrites)
        assertFalse(detect("how many tasks are due today").hasWrites)
    }

    @Test
    fun profile_command_wins_over_memory_verb() {
        // "note … to <profile>" is a profile write, not a memory one.
        val result = detect("note to llm-persona that the user likes concise replies")
        assertEquals(1, result.profileAppends.size)
        assertEquals("llm-persona", result.profileAppends[0].profileKey)
        assertTrue(result.memoryWrites.isEmpty())
    }
}
