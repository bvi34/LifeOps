package com.advisor.app.logic

/** What actually happened when a [TaskCommand] was carried out — reported, never assumed. */
sealed interface TaskWriteResult {

    /**
     * The task exists now. [target] is the operation/aspect/category it was filed under (null when the
     * user named none), and [unmatchedTarget] is a name they gave that matched nothing in the app —
     * the task is still created, and the reply says so rather than quietly dropping the intent.
     */
    data class Created(
        val title: String,
        val target: String? = null,
        val unmatchedTarget: String? = null,
        val weekLabel: String? = null
    ) : TaskWriteResult

    /** The week already holds a task with this title; the incumbent survives (the app's own rule). */
    data class Duplicate(val title: String) : TaskWriteResult

    /** Nothing was written, and this is why. */
    data class Failed(val reason: String) : TaskWriteResult
}

/**
 * The Advisor's single **write** capability into a hosted app: creating a task the user explicitly
 * asked for. Everything else about the other apps stays read-only ([com.advisor.app.data.source.KnowledgeSource]).
 *
 * It is an interface here, in framework-free `logic/`, for the same reason the knowledge sources are:
 * the pipeline that decides *whether* to write must stay testable on the JVM, while the Android-side
 * implementation ([com.advisor.app.data.action.LifeOpsTaskWriter]) owns the how — resolving the named
 * target and calling the owning app's own connection route, so the task is created by exactly the same
 * code path the app's own UI uses.
 */
interface TaskWriter {

    /** The app this writes into; matched against [TaskCommand.app] and the permission grant. */
    val app: SourceApp

    suspend fun create(command: TaskCommand): TaskWriteResult
}
