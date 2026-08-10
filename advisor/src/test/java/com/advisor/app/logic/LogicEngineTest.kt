package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicEngineTest {

    @Test
    fun noop_engine_contributes_nothing() {
        val input = LogicInput(
            question = "what should I do next",
            identity = Identity(name = "Sam"),
            retrieved = emptyList(),
            memories = listOf(MemoryRecord("m1", "focus on the roadmap"))
        )
        val output = NoOpLogicEngine.process(input)
        assertTrue(output.derivedContext.isEmpty())
        assertEquals(LogicOutput.EMPTY, output)
    }

    @Test
    fun derived_context_flows_into_the_prompt() {
        // A future engine returns lines; assembly must surface them in the REASONING section.
        val engine = object : LogicEngine {
            override fun process(input: LogicInput) =
                LogicOutput(listOf("2 tasks are overdue"))
        }
        val out = engine.process(LogicInput("q", Identity.EMPTY, emptyList(), emptyList()))
        val prompt = PromptAssembler.assemble("q", emptyList(), derived = out.derivedContext)
        assertTrue(prompt.render().contains("REASONING"))
        assertTrue(prompt.render().contains("2 tasks are overdue"))
    }
}
