package com.advisor.app.logic

/**
 * Everything the logic engine gets to look at for one question: the question itself, the user's
 * [Identity], the app data the retriever surfaced, and the long-term memories recalled. It is a
 * read-only snapshot — the engine derives context, it does not mutate the pipeline.
 */
data class LogicInput(
    val question: String,
    val identity: Identity,
    val retrieved: List<RetrievedChunk>,
    val memories: List<MemoryRecord>
)

/**
 * What the logic engine contributes: extra context/derived facts to inject into the prompt's
 * REASONING section (e.g. "3 tasks are overdue", "pantry is low on 2 recipe ingredients",
 * a computed deadline, a resolved contradiction). Kept as plain lines so any engine — rules,
 * heuristics, a small solver — can emit into it.
 */
data class LogicOutput(val derivedContext: List<String>) {
    companion object {
        val EMPTY = LogicOutput(emptyList())
    }
}

/**
 * The seam for the **logic engine** — a future component that adds context/logic to the model
 * beyond raw retrieval (computed facts, cross-app reasoning, constraints). It sits between retrieval
 * and prompt assembly: `permissions → retrieve → recall → **logic** → prompt → model`. The real
 * engine lands in a later commit; today [NoOpLogicEngine] is wired in so the pipeline already calls
 * through the seam and nothing else has to change when the real one arrives.
 */
interface LogicEngine {
    fun process(input: LogicInput): LogicOutput
}

/** The default until the real logic engine ships: contributes nothing. */
object NoOpLogicEngine : LogicEngine {
    override fun process(input: LogicInput): LogicOutput = LogicOutput.EMPTY
}
