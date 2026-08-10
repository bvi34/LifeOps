package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallTalkTest {

    private val allApps = setOf(SourceApp.LIFEOPS, SourceApp.CITATION, SourceApp.LOGISTICS)

    // --- greetings ---------------------------------------------------------------------------------

    @Test
    fun bare_greeting_is_recognised() {
        val reply = SmallTalk.detect("hi")
        assertEquals(SmallTalk.Kind.GREETING, reply?.kind)
    }

    @Test
    fun greeting_variants_and_phrases_are_recognised() {
        for (hello in listOf("hey", "hello there", "yo", "howdy", "good morning", "how are you?")) {
            assertEquals("failed on: $hello", SmallTalk.Kind.GREETING, SmallTalk.detect(hello)?.kind)
        }
    }

    @Test
    fun greeting_uses_the_users_name_when_known() {
        val reply = SmallTalk.detect("hello", SmallTalk.Context(userName = "Brenden"))
        assertTrue(reply!!.text.contains("Brenden"))
    }

    @Test
    fun greeting_offers_the_enabled_apps() {
        val reply = SmallTalk.detect("hi", SmallTalk.Context(grantedApps = setOf(SourceApp.LIFEOPS)))
        assertTrue(reply!!.text.contains("LifeOps"))
    }

    // --- thanks / farewell / acknowledgement -------------------------------------------------------

    @Test
    fun thanks_is_recognised() {
        assertEquals(SmallTalk.Kind.THANKS, SmallTalk.detect("thanks")?.kind)
        assertEquals(SmallTalk.Kind.THANKS, SmallTalk.detect("thank you so much")?.kind)
    }

    @Test
    fun farewell_is_recognised() {
        assertEquals(SmallTalk.Kind.FAREWELL, SmallTalk.detect("bye")?.kind)
        assertEquals(SmallTalk.Kind.FAREWELL, SmallTalk.detect("see you later")?.kind)
    }

    @Test
    fun acknowledgement_is_recognised() {
        assertEquals(SmallTalk.Kind.ACKNOWLEDGEMENT, SmallTalk.detect("ok")?.kind)
        assertEquals(SmallTalk.Kind.ACKNOWLEDGEMENT, SmallTalk.detect("got it")?.kind)
        assertEquals(SmallTalk.Kind.ACKNOWLEDGEMENT, SmallTalk.detect("sounds good")?.kind)
    }

    @Test
    fun the_closing_kind_wins_when_several_are_present() {
        // "thanks, bye" is both gratitude and a farewell — the closing note wins.
        assertEquals(SmallTalk.Kind.FAREWELL, SmallTalk.detect("thanks bye")?.kind)
    }

    // --- capability / identity meta-questions ------------------------------------------------------

    @Test
    fun capability_question_is_recognised() {
        for (q in listOf("what can you do?", "what can you do", "hey, what can you do?", "help",
                "how do you work?")) {
            assertEquals("failed on: $q", SmallTalk.Kind.CAPABILITY, SmallTalk.detect(q)?.kind)
        }
    }

    @Test
    fun capability_lists_the_enabled_apps_with_examples() {
        val reply = SmallTalk.detect("what can you do?", SmallTalk.Context(grantedApps = allApps))
        val text = reply!!.text
        assertTrue(text.contains("LifeOps"))
        assertTrue(text.contains("Citation"))
        assertTrue(text.contains("Logistics"))
        assertTrue(text.contains("pantry")) // a Logistics example
        assertTrue(text.contains("remember")) // ties chit-chat back to recall/notes
    }

    @Test
    fun capability_points_at_permissions_when_nothing_is_enabled() {
        val reply = SmallTalk.detect("what can you do?", SmallTalk.Context(grantedApps = emptySet()))
        assertTrue(reply!!.text.contains("Permissions"))
    }

    @Test
    fun identity_question_is_recognised() {
        for (q in listOf("who are you?", "what are you?", "what's your name?",
                "tell me about yourself")) {
            assertEquals("failed on: $q", SmallTalk.Kind.IDENTITY, SmallTalk.detect(q)?.kind)
        }
    }

    @Test
    fun identity_reply_uses_the_persona_name() {
        val reply = SmallTalk.detect("who are you?", SmallTalk.Context(assistantName = "Ava"))
        assertTrue(reply!!.text.contains("Ava"))
    }

    // --- real questions must fall through (return null) --------------------------------------------

    @Test
    fun a_data_question_is_not_small_talk() {
        assertNull(SmallTalk.detect("what tasks are due this week?"))
        assertNull(SmallTalk.detect("how many books have I read?"))
        assertNull(SmallTalk.detect("what's running low in the pantry?"))
    }

    @Test
    fun a_greeting_in_front_of_a_real_question_falls_through() {
        // The greeting word is present, but "what's due today" is real content — don't swallow it.
        assertNull(SmallTalk.detect("hi, what's due today?"))
    }

    @Test
    fun a_question_about_the_user_is_not_assistant_identity() {
        // "who am I" is about the user (handled downstream by IdentityQuestions), not small talk.
        assertNull(SmallTalk.detect("who am I?"))
        assertNull(SmallTalk.detect("what is my name?"))
    }

    @Test
    fun capability_lookalikes_that_carry_content_fall_through() {
        assertNull(SmallTalk.detect("what do you know about Dune?"))
        assertNull(SmallTalk.detect("what can you tell me about my tasks?"))
    }

    // --- persona name derivation -------------------------------------------------------------------

    @Test
    fun assistant_name_defaults_when_no_persona_is_set() {
        assertEquals(SmallTalk.DEFAULT_NAME, SmallTalk.assistantNameFrom(emptyList()))
    }

    @Test
    fun assistant_name_is_taken_from_the_persona_profile() {
        val persona = Profile(
            key = "llm-persona",
            name = "LLM Persona",
            kind = ProfileKind.PERSONA,
            entries = listOf(ProfileEntry("you are called Ava"))
        )
        assertEquals("Ava", SmallTalk.assistantNameFrom(listOf(persona)))
    }

    @Test
    fun the_most_recent_persona_naming_wins() {
        val persona = Profile(
            key = "llm-persona",
            name = "LLM Persona",
            kind = ProfileKind.PERSONA,
            entries = listOf(
                ProfileEntry("you are called Ava"),
                ProfileEntry("actually your name is Nova")
            )
        )
        assertEquals("Nova", SmallTalk.assistantNameFrom(listOf(persona)))
    }
}
