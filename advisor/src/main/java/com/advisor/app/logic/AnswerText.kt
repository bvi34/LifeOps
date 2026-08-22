package com.advisor.app.logic

/**
 * What the user should actually see of a model's answer.
 *
 * The model writes more than prose: `@remember(...)`, `@memorize:` and `@relevance(n):` lines are
 * instructions to the app, acted on and then removed. Removing them lived inline in the repository as
 * a chain of three `strip` calls, which was fine while the answer only appeared once it was finished.
 * Streaming makes it a question with two answers — a finished reply, and a reply mid-sentence — so it
 * belongs in one pure, tested place rather than being re-derived at each call site.
 */
object AnswerText {

    /** The finished answer: every directive the model emitted removed. */
    fun finished(raw: String): String =
        RelevanceDirectives.strip(MemoryDirectives.strip(ProfileDirectives.strip(raw))).trim()

    /**
     * The answer as it stands mid-generation. Beyond [finished], it also hides a directive that is
     * still being *written*: the strip patterns match whole lines, so an `@memorize:` half-typed on
     * the last line matches nothing yet and would otherwise appear on screen — the app's private
     * bookkeeping, shown to the user for a second and then vanishing.
     *
     * Only the final line is treated this way, and only while it looks like a directive: a completed
     * directive followed by more prose is already handled by [finished], and prose that merely
     * mentions an @ mid-line is left alone.
     */
    fun inProgress(raw: String): String {
        val text = finished(raw)
        val lastBreak = text.lastIndexOf('\n')
        val lastLine = text.substring(lastBreak + 1)
        if (!lastLine.trimStart().startsWith("@")) return text
        return if (lastBreak < 0) "" else text.substring(0, lastBreak).trimEnd()
    }
}
