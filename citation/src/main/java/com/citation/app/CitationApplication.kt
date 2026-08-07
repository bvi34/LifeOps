package com.citation.app

import android.app.Application
import android.content.Context
import com.citation.app.data.CitationRepository
import com.citation.app.data.OreillyAccess
import com.citation.app.data.db.CitationDatabase
import com.citation.app.data.store.FileStores
import com.citation.app.work.Ao3Scheduler
import com.citation.app.work.RoyalRoadScheduler
import com.citation.app.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Citation's runtime. It used to *be* the `Application`, but under the Operations Sandbox container
 * a single [Application] ([com.operations.sandbox.SandboxApplication]) hosts both LifeOps and
 * Citation, so this is now a plain holder the sandbox constructs via [install]. Feature code reaches
 * it through [get]/[getOrNull] instead of casting the Application.
 *
 * The class name is unchanged so existing typed references keep compiling. [repository] is still
 * built asynchronously (it restores the key allocator and mailbox from Room); callers await it.
 */
class CitationApplication private constructor(private val app: Application) {

    private val appScope = CoroutineScope(SupervisorJob())

    lateinit var repository: Deferred<CitationRepository>
        private set

    /** Build the repository and register Citation's periodic jobs, once. Called by the sandbox. */
    private fun start() {
        val db = CitationDatabase.get(app)
        val files = FileStores(app)
        val oreillyAccess = OreillyAccess(app)
        repository = appScope.async { CitationRepository.create(db, files, oreillyAccess) }
        // Register the periodic RR jobs (poll favourites, advance backfill, evict stale cache).
        RoyalRoadScheduler.schedule(app)
        // Same set of periodic jobs for Archive of Our Own.
        Ao3Scheduler.schedule(app)
        // Register the periodic sync round with LifeOps (drain outbox, consume acquire intents).
        SyncWorker.schedule(app)
    }

    companion object {
        @Volatile
        private var instance: CitationApplication? = null

        /** Construct Citation's runtime against the hosting [app] and start it, once. */
        fun install(app: Application): CitationApplication =
            instance ?: synchronized(this) {
                instance ?: CitationApplication(app).also { instance = it; it.start() }
            }

        /** The installed runtime. Throws if the host never called [install] (a wiring bug). */
        fun get(context: Context): CitationApplication =
            instance ?: error("CitationApplication.install() was never called by the hosting Application")

        /** The installed runtime, or null — for background entry points that must degrade gracefully. */
        fun getOrNull(): CitationApplication? = instance
    }
}
