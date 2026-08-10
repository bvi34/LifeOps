package com.advisor.app.logic

/**
 * Everything a function may compute over: the question plus all the data the repository can hand it,
 * already permission-filtered ([corpus] holds only granted apps). A function reads whichever parts it
 * needs and ignores the rest.
 */
data class FunctionRequest(
    val question: String,
    val corpus: List<KnowledgeDocument> = emptyList(),
    val conversation: List<ConversationTurn> = emptyList(),
    val memories: List<MemoryRecord> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val identity: Identity = Identity.EMPTY,
    val grantedApps: Set<SourceApp> = emptySet(),
    val deniedApps: Set<SourceApp> = emptySet()
)

/** A computed, grounded answer from a function — the text to show plus any documents it drew on. */
data class FunctionResult(
    val text: String,
    val citations: List<KnowledgeDocument> = emptyList()
)

/**
 * A discrete **capability** the engine can dispatch a question to instead of answering by retrieval.
 * Where extractive RAG can only *surface* rows (and so answers "how many times did I say X" by dumping
 * whatever text happens to share a word), a function **computes** over the user's own data — a count,
 * a tally, a lookup — and returns the actual result.
 *
 * This is the "distribute various functions" seam: the [FunctionRouter] holds a list of these and hands
 * a question to the first that [handles] it, before the normal retrieve → C3A → model path runs. Each
 * function is pure and JVM-testable, exactly like the rest of `logic/`, and new capabilities plug in by
 * being added to the router.
 */
interface AdvisorFunction {

    /** A short, stable identifier for the capability (for logging and tests). */
    val name: String

    /** A cheap check on the question alone: is this a request this function should compute? */
    fun handles(question: String): Boolean

    /** Compute the grounded answer. Only called when [handles] returned true for the same question. */
    fun run(request: FunctionRequest): FunctionResult
}
