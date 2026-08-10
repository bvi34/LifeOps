package com.advisor.app.logic

/**
 * Which apps the Advisor is allowed to read. This is the "perms for all the other apps" half of the
 * feature, and it is **privacy-first by construction**: an app is *denied until the user explicitly
 * grants it*, so the local model can never see data the user hasn't opted into. LifeOps' whole ethos
 * is local-only, receipts-kept data; the assistant that reads across it has to honour the same
 * contract, per app, and make the boundary visible.
 *
 * The value is immutable; toggling returns a new instance, which the repository persists.
 */
data class AdvisorPermissions(val granted: Set<SourceApp> = emptySet()) {

    fun isGranted(app: SourceApp): Boolean = app in granted

    fun grant(app: SourceApp): AdvisorPermissions = copy(granted = granted + app)

    fun revoke(app: SourceApp): AdvisorPermissions = copy(granted = granted - app)

    /** Grant or revoke [app] in one call — the shape a settings toggle wants. */
    fun with(app: SourceApp, allowed: Boolean): AdvisorPermissions =
        if (allowed) grant(app) else revoke(app)

    val isEmpty: Boolean get() = granted.isEmpty()

    /** Keep only the documents the user has granted — the gate every retrieval passes through. */
    fun filter(documents: List<KnowledgeDocument>): List<KnowledgeDocument> =
        documents.filter { it.source in granted }

    companion object {
        /** The safe default for a fresh install: read nothing until asked. */
        val NONE = AdvisorPermissions(emptySet())

        val ALL = AdvisorPermissions(SourceApp.entries.toSet())
    }
}
