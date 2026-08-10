package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class C3AEngineTest {

    private val engine = C3AEngine()

    private fun chunk(title: String, body: String) =
        RetrievedChunk(KnowledgeDocument("lifeops:task:1", SourceApp.LIFEOPS, "task", title, body), 3.0)

    private fun input(
        question: String,
        retrieved: List<RetrievedChunk> = emptyList(),
        memories: List<MemoryRecord> = emptyList(),
        profiles: List<Profile> = emptyList(),
        granted: Set<SourceApp> = SourceApp.entries.toSet(),
        justAsked: Boolean = false
    ) = LogicInput(
        question = question,
        identity = Identity.EMPTY,
        retrieved = retrieved,
        memories = memories,
        profiles = profiles,
        grantedApps = granted,
        deniedApps = SourceApp.entries.toSet() - granted,
        justAsked = justAsked
    )

    @Test
    fun answers_when_grounded() {
        val out = engine.process(
            input("what tasks are due", retrieved = listOf(chunk("Mow the lawn", "Task: Mow the lawn. due Friday")))
        )
        assertEquals(EngineDecision.ANSWER, out.decision)
        assertFalse(out.asksUser)
        assertTrue(out.derivedContext.isNotEmpty())
    }

    @Test
    fun clarifies_when_nothing_matches() {
        val out = engine.process(input("what did I decide about the quarterly budget reforecast"))
        assertEquals(EngineDecision.CLARIFY, out.decision)
        assertNotNull(out.clarification)
    }

    @Test
    fun clarifies_on_contradiction_rather_than_picking_one() {
        val memories = listOf(
            MemoryRecord("m1", "The user prefers oat milk in coffee"),
            MemoryRecord("m2", "The user does not prefer oat milk in coffee")
        )
        // Even with strong lexical grounding, conflicting facts must not be answered from.
        val out = engine.process(
            input("what milk do I prefer", retrieved = listOf(chunk("coffee", "milk preference")), memories = memories)
        )
        assertEquals(EngineDecision.CLARIFY, out.decision)
        assertTrue(out.contradictions.isNotEmpty())
        assertTrue(out.clarification!!.contains("conflict"))
    }

    @Test
    fun ambiguous_reference_is_clarified() {
        val out = engine.process(input("can you finish it"))
        assertEquals(EngineDecision.CLARIFY, out.decision)
        assertTrue(out.clarification!!.contains("which", ignoreCase = true))
    }

    @Test
    fun investigates_when_topic_needs_a_denied_app() {
        // Asks about pantry, but Logistics isn't granted.
        val out = engine.process(input("how much flour is in my pantry", granted = setOf(SourceApp.LIFEOPS)))
        assertEquals(EngineDecision.INVESTIGATE, out.decision)
        assertTrue(out.clarification!!.contains("Logistics"))
    }

    @Test
    fun does_not_ask_twice_in_a_row() {
        // Ungrounded, but the previous turn was already a clarification → proceed instead of looping.
        val out = engine.process(input("tell me more about the reforecast", justAsked = true))
        assertEquals(EngineDecision.ANSWER, out.decision)
    }

    @Test
    fun contradiction_still_stops_even_after_asking() {
        val memories = listOf(
            MemoryRecord("m1", "The user is vegetarian"),
            MemoryRecord("m2", "The user is not vegetarian")
        )
        val out = engine.process(input("what should I cook", memories = memories, justAsked = true))
        assertEquals(EngineDecision.CLARIFY, out.decision)
    }

    @Test
    fun plain_greeting_is_answered() {
        assertEquals(EngineDecision.ANSWER, engine.process(input("hello")).decision)
        assertEquals(EngineDecision.ANSWER, engine.process(input("thanks")).decision)
    }
}
