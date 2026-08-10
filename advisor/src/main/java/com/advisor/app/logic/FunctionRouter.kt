package com.advisor.app.logic

/**
 * The engine's function dispatcher. It holds the registered [AdvisorFunction]s and, for a question,
 * returns the first one that [AdvisorFunction.handles] it — or null when none apply, in which case the
 * repository falls through to the normal retrieve → C3A → model pipeline.
 *
 * Order matters: earlier functions win, so put the more specific ones first. Registering a new
 * capability is just adding it to [DEFAULT].
 */
class FunctionRouter(private val functions: List<AdvisorFunction>) {

    /** The function that should handle [question], or null to fall through to normal answering. */
    fun handler(question: String): AdvisorFunction? =
        functions.firstOrNull { it.handles(question) }

    /** The registered capabilities, for display/diagnostics. */
    fun capabilities(): List<String> = functions.map { it.name }

    companion object {
        /** The capabilities Advisor ships with. */
        val DEFAULT = FunctionRouter(listOf(WordUsageFunction()))
    }
}
