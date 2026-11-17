package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failures behind "the Advisor feels robotic", each pinned: a persona named "Name: Ava" that the
 * assistant ignored, profile bookkeeping leaking into replies, the model re-saving what it was shown,
 * and questions about the user being answered from unrelated app rows.
 */
class PersonableAdvisorTest {

    private val userProfile = Profile(
        key = "user",
        name = "User",
        kind = ProfileKind.USER,
        summary = "Freeform, long-running notes about the user (structured facts live in Identity).",
        entries = listOf(
            ProfileEntry("Name: Brenden Villaruel"),
            ProfileEntry("Brenden Villaruel", ProfileEntry.AUTHOR_ADVISOR),
            ProfileEntry("Prefers short answers")
        )
    )

    private val persona = Profile(
        key = "llm-persona",
        name = "LLM Persona",
        kind = ProfileKind.PERSONA,
        summary = "How the assistant should behave — persona, tone, and standing instructions.",
        entries = listOf(ProfileEntry("Name: Ava"), ProfileEntry("Be playful and a little witty"))
    )

    // --- the assistant's name ----------------------------------------------------------------------

    @Test
    fun a_name_colon_entry_names_the_assistant() {
        assertEquals("Ava", SmallTalk.assistantNameFrom(listOf(persona)))
    }

    @Test
    fun the_persona_leads_the_system_turn_as_instructions() {
        val prompt = PromptAssembler.assemble("what's your name", emptyList(), profiles = listOf(userProfile, persona))
        assertTrue(prompt.system.startsWith("Your name is Ava."))
        assertTrue(prompt.system.contains("never as \"Advisor\""))
        assertTrue(prompt.system.contains("- Be playful and a little witty"))
        assertTrue(prompt.system.endsWith(PromptAssembler.SYSTEM))
        // Not repeated in the per-question context, where it read as data about someone.
        assertFalse(prompt.render().contains("persona, tone, and standing instructions"))
    }

    @Test
    fun no_persona_profile_leaves_the_system_prompt_untouched() {
        val prompt = PromptAssembler.assemble("hi", emptyList(), profiles = listOf(userProfile))
        assertEquals(PromptAssembler.SYSTEM, prompt.system)
    }

    @Test
    fun the_shipped_prompt_no_longer_hardcodes_a_name() {
        assertFalse(PromptAssembler.SYSTEM.contains("You are Advisor"))
    }

    // --- what the model is shown about the user ----------------------------------------------------

    @Test
    fun the_user_profile_folds_into_identity_without_its_summary_or_duplicates() {
        val identity = Identity(name = "Brenden Villaruel")
        val prompt = PromptAssembler.assemble("who am i", emptyList(), identity = identity, profiles = listOf(userProfile))
        val text = prompt.render()
        assertFalse(text.contains("Freeform, long-running notes"))
        assertFalse(text.contains("PROFILES ("))
        assertTrue(text.contains("- Prefers short answers"))
        assertEquals(1, prompt.aboutUserLines().count { it.contains("Brenden Villaruel") })
    }

    @Test
    fun project_profiles_still_render_with_their_summary() {
        val project = Profile("garden", "Garden", ProfileKind.PROJECT, "Backyard plans", listOf(ProfileEntry("Tomatoes in May")))
        val text = PromptAssembler.assemble("q", emptyList(), profiles = listOf(project)).render()
        assertTrue(text.contains("## Garden — Backyard plans"))
        assertTrue(text.contains("- Tomatoes in May"))
    }

    // --- clean replies -----------------------------------------------------------------------------

    @Test
    fun a_mid_sentence_remember_directive_is_hidden_but_not_applied() {
        val raw = "I see that you are Brenden. @remember(User): Brenden Villaruel — Freeform notes"
        assertEquals("I see that you are Brenden.", AnswerText.finished(raw))
        assertTrue(ProfileDirectives.parse(raw).isEmpty())
    }

    @Test
    fun a_mid_sentence_memorize_directive_is_hidden() {
        assertEquals("Noted!", MemoryDirectives.strip("Noted! @memorize: works at SWCA #work"))
    }

    @Test
    fun profile_and_memory_labels_are_removed_but_numbered_citations_stay() {
        val text = "You are Brenden Villaruel [User] [User]. You have a dentist visit [2] and [M1] a goal."
        assertEquals(
            "You are Brenden Villaruel. You have a dentist visit [2] and a goal.",
            AnswerText.withoutContextTags(text, listOf(userProfile, persona))
        )
    }

    @Test
    fun ordinary_bracketed_prose_is_left_alone() {
        val text = "Try the [optional] step."
        assertEquals(text, AnswerText.withoutContextTags(text))
    }

    // --- what the model may save -------------------------------------------------------------------

    @Test
    fun the_model_cannot_resave_what_it_was_shown() {
        val known = listOf("Name: Brenden Villaruel")
        assertFalse(ProfileDirectives.isNew(ProfileAppend("user", "Brenden Villaruel"), userProfile, known))
        assertFalse(ProfileDirectives.isNew(ProfileAppend("user", "prefers short answers."), userProfile, known))
        assertFalse(
            ProfileDirectives.isNew(
                ProfileAppend("user", "Brenden — Freeform, long-running notes about the user (structured facts live in Identity)."),
                userProfile,
                known
            )
        )
    }

    @Test
    fun a_genuinely_new_fact_is_saved() {
        assertTrue(ProfileDirectives.isNew(ProfileAppend("user", "Works at SWCA"), userProfile, listOf("Name: Brenden Villaruel")))
        assertTrue(ProfileDirectives.isNew(ProfileAppend("garden", "Tomatoes in May"), null))
    }

    // --- questions about the user ------------------------------------------------------------------

    @Test
    fun naming_the_user_counts_as_asking_about_them() {
        assertTrue(IdentityQuestions.isAboutUser("what do you know about Brenden Villaruel", "Brenden Villaruel"))
        assertTrue(IdentityQuestions.isAboutUser("who is brenden?", "Brenden Villaruel"))
        assertTrue(IdentityQuestions.isAboutUser("what do you know about me", ""))
        assertFalse(IdentityQuestions.isAboutUser("what tasks does Brenden have", "Brenden Villaruel"))
        assertFalse(IdentityQuestions.isAboutUser("what's due today", "Brenden Villaruel"))
    }

    @Test
    fun memories_about_the_user_are_recalled_for_who_am_i() {
        val memories = listOf(
            MemoryRecord("1", "I work at SWCA"),
            MemoryRecord("2", "Brenden Villaruel works at SWCA"),
            MemoryRecord("3", "The furnace filter is 16x25"),
            MemoryRecord("4", "Garage code", pinned = true)
        )
        val recalled = MemoryRecall.aboutUser(memories, "Brenden Villaruel").map { it.id }
        assertEquals(listOf("4", "1", "2"), recalled)
        assertFalse("3" in recalled)
    }

    @Test
    fun whats_your_name_is_still_recognised_as_small_talk() {
        val reply = SmallTalk.detect("what's your name", SmallTalk.Context(assistantName = "Ava"))
        assertNotNull(reply)
        assertEquals(SmallTalk.Kind.IDENTITY, reply!!.kind)
        assertTrue(reply.text.contains("Ava"))
        assertNull(SmallTalk.detect("what's due today"))
    }
}
