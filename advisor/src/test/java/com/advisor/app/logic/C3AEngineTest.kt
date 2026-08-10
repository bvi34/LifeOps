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

    private fun input(question: String, profiles: List<Profile> = emptyList(), identity: Identity = Identity.EMPTY) =
        LogicInput(
            question = question,
            identity = identity,
            retrieved = emptyList(),
            memories = emptyList(),
            profiles = profiles
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
}
