package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grounding behaviour for questions about the user. A stored "Name: …" fact shares no words with
 * "who am I", so the engine must recognise the identity intent and answer from the profile it holds
 * rather than clarifying about something plainly on record.
 */
class C3AEngineTest {

    private val engine = C3AEngine()

    private fun userProfile() = Profile(
        key = "user", name = "User", kind = ProfileKind.USER,
        entries = listOf(ProfileEntry("Name: Brenden Villaruel"))
    )

    private fun input(
        question: String,
        profiles: List<Profile> = emptyList(),
        identity: Identity = Identity.EMPTY,
        conversation: List<ConversationTurn> = emptyList()
    ) =
        LogicInput(
            question = question,
            identity = identity,
            retrieved = emptyList(),
            memories = emptyList(),
            profiles = profiles,
            conversation = conversation
        )

    @Test
    fun who_am_i_answers_from_the_user_profile_instead_of_clarifying() {
        val out = engine.process(input("who am I", profiles = listOf(userProfile())))
        assertEquals(EngineDecision.ANSWER, out.decision)
    }

    @Test
    fun who_am_i_answers_from_identity_when_there_is_no_profile() {
        val out = engine.process(input("who am I", identity = Identity(name = "Brenden Villaruel")))
        assertEquals(EngineDecision.ANSWER, out.decision)
    }

    @Test
    fun identity_question_with_nothing_on_record_still_clarifies() {
        val out = engine.process(input("who am I"))
        assertEquals(EngineDecision.CLARIFY, out.decision)
    }

    @Test
    fun what_is_my_name_grounds_on_the_matching_profile_entry() {
        val out = engine.process(input("what is my name", profiles = listOf(userProfile())))
        assertEquals(EngineDecision.ANSWER, out.decision)
    }

    @Test
    fun ambiguous_reference_with_no_conversation_clarifies() {
        // "it" has nothing to bind to on a cold open — the engine asks rather than guessing.
        val out = engine.process(input("tell me about it"))
        assertEquals(EngineDecision.CLARIFY, out.decision)
    }

    @Test
    fun ambiguous_reference_answers_once_the_conversation_gives_context() {
        // Same question, but the chat is underway — "it" can bind to the prior turns, so the engine
        // proceeds instead of re-asking. This is the "look at the whole context window" behaviour.
        val history = listOf(
            ConversationTurn(fromUser = true, text = "tell me about Dune"),
            ConversationTurn(fromUser = false, text = "Dune is a novel by Frank Herbert")
        )
        val out = engine.process(input("tell me about it", conversation = history))
        assertEquals(EngineDecision.ANSWER, out.decision)
    }

    @Test
    fun answer_notes_report_the_conversation_context() {
        val history = listOf(ConversationTurn(fromUser = true, text = "what tasks are due"))
        val out = engine.process(input("what is my name", profiles = listOf(userProfile()), conversation = history))
        assertEquals(EngineDecision.ANSWER, out.decision)
        assert(out.derivedContext.any { it.contains("conversation turn") }) {
            "expected a reasoning note about conversation context, got ${out.derivedContext}"
        }
    }
}
