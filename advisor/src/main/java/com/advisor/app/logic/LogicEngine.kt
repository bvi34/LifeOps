package com.advisor.app.logic

/**
 * What the unifying engine decided to do with a question.
 *
 * The governing principle is: **"Not knowing is acceptable. Being wrong without asking clarification
 * is not."** So when grounding is thin, a reference is ambiguous, or sources conflict, the engine
 * chooses to *ask* rather than let the model resolve the uncertainty internally.
 */
enum class EngineDecision {
    /** Enough to answer — proceed to the model with any derived context. */
    ANSWER,

    /** Ask the user a clarifying question instead of answering. */
    CLARIFY,

    /** The answer needs a source that isn't available yet (e.g. a denied app); ask before using it. */
    INVESTIGATE
}

/** Something the engine can't currently resolve, and why. */
data class Uncertainty(val about: String, val reason: String)

/** Two statements the engine believes conflict. */
data class Contradiction(val statementA: String, val statementB: String, val note: String = "")

/**
 * Everything the engine gets to look at for one question: the question, the user's [Identity], the
 * standing [Profile]s, the app data the retriever surfaced, the long-term memories recalled, and
 * which apps are granted vs. denied. [justAsked] is true when the previous assistant turn was itself
 * a clarification — the engine uses it to avoid asking twice in a row (it asked once; now it may
 * proceed).
 */
data class LogicInput(
    val question: String,
    val identity: Identity,
    val retrieved: List<RetrievedChunk>,
    val memories: List<MemoryRecord>,
    val profiles: List<Profile> = emptyList(),
    val grantedApps: Set<SourceApp> = emptySet(),
    val deniedApps: Set<SourceApp> = emptySet(),
    val justAsked: Boolean = false,
    /** The recent chat turns leading up to [question], oldest first — context for follow-ups. */
    val conversation: List<ConversationTurn> = emptyList()
)

/**
 * The engine's verdict. [derivedContext] are lines injected into the prompt's REASONING section when
 * answering; [clarification] is the ready-to-show question when the decision is [EngineDecision.CLARIFY]
 * or [EngineDecision.INVESTIGATE]. [uncertainties] and [contradictions] carry the structured "why".
 *
 * [derivedContext] stays the first parameter so existing callers/tests keep working; everything else
 * has a default, and [EMPTY] is a plain "answer, nothing derived" verdict.
 */
data class LogicOutput(
    val derivedContext: List<String> = emptyList(),
    val decision: EngineDecision = EngineDecision.ANSWER,
    val clarification: String? = null,
    val uncertainties: List<Uncertainty> = emptyList(),
    val contradictions: List<Contradiction> = emptyList(),
    val rationale: String? = null
) {
    val asksUser: Boolean get() = decision != EngineDecision.ANSWER

    companion object {
        val EMPTY = LogicOutput()
    }
}

/**
 * The unifying engine seam. An implementation coordinates the other components (LLM + databases +
 * JSON) into a single reasoning workflow: context assembly, retrieval framing, uncertainty and
 * contradiction detection, and the decision of whether to answer, ask for clarification, or flag that
 * external investigation is needed. It sits between recall and prompt assembly:
 * `permissions → retrieve → recall → **engine** → prompt → model`.
 */
interface LogicEngine {
    fun process(input: LogicInput): LogicOutput
}

/** A pass-through engine that always answers and contributes nothing — the pre-C3A default. */
object NoOpLogicEngine : LogicEngine {
    override fun process(input: LogicInput): LogicOutput = LogicOutput.EMPTY
}
