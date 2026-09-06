package com.project.app

import android.app.Application
import android.content.Context
import com.lifeops.app.connection.TaskCompletionBus
import com.project.app.data.db.ProjectDatabase
import com.project.app.data.prefs.ProjectPrefs
import com.project.app.data.repository.CardPublisher
import com.operations.backupkit.AppId
import com.project.app.connection.ProjectConnections
import com.project.app.data.repository.ProjectRepository
import com.repository.app.RepositoryApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Project's tiny runtime container, mirroring the other hosted apps: the Operations Sandbox
 * [Application] calls [install] once, and the activity resolves it with [get].
 *
 * Everything is lazy, so a suite install where nobody ever opens Project pays nothing for it — no
 * database file is created until the shelf is first drawn. There is no sync service here: Project
 * has no peer to reconcile with, which is deliberate.
 *
 * There is one piece of background work, and it points outward. A board card with a due date puts
 * itself on the LifeOps week, and ticking it there finishes it here — so [install] registers for
 * LifeOps' completion announcements. That registration is deliberately cheap and deliberately
 * gated: it is a listener on a bus, and it does nothing at all until this install has actually
 * published something, so the lazy-database promise above survives a household that never dates a
 * card. Reminding you to write is still LifeOps' job; this is Project handing it the deadline.
 */
class ProjectApp private constructor(private val app: Application) {

    val database by lazy { ProjectDatabase.getInstance(app) }
    val prefs by lazy { ProjectPrefs(app) }
    val repository by lazy {
        ProjectRepository(
            dao = database.projectDao(),
            // A renamed record whose drawer on the household's shelf still says the old name is a
            // drawer nobody finds again. Repository stores a label rather than a foreign key, so
            // this is the push that keeps it true — resolved here because finding the shelf is an
            // Android question and the repository is a thing that takes a DAO.
            relabelDocuments = { recordKey, label ->
                runCatching {
                    RepositoryApp.get(app).documents.relabel(AppId.PROJECT.key, recordKey, label)
                }
            }
        )
    }
    /**
     * Project's connection layer — `/v1/Project/local/…`, the suite's second dispatcher.
     *
     * Lazy like everything else here: an install where nobody calls a route never builds it, and
     * building it does not open the database.
     */
    val connectionDispatcher by lazy { ProjectConnections.buildDispatcher(repository) }

    val publisher by lazy {
        CardPublisher(
            store = repository,
            onPublished = { prefs.hasPublishedTasks = true }
        )
    }

    /**
     * Rounds run here rather than on a screen's scope: a tick announced by LifeOps arrives with no
     * screen behind it, and a round started by the last thing you did in Project should finish even
     * if you left immediately afterwards.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Reconcile the board's due dates with the week, in the background.
     *
     * Safe to call often and from anywhere — the round is idempotent, so the honest thing is to run
     * it whenever something might have changed rather than to reason about when it can be skipped.
     */
    fun syncNow() {
        scope.launch { publisher.round() }
    }

    /** Take tasks off the week for cards that are about to stop existing. */
    fun retireTasks(taskIds: List<String>) {
        if (taskIds.isEmpty()) return
        scope.launch { publisher.retire(taskIds) }
    }

    private fun listenForCompletions() {
        TaskCompletionBus.register {
            // Straight onto the background scope: LifeOps resumes this on whichever dispatcher
            // ticked the task, which for a tap on a task row is the main thread — and even reading
            // a preference for the first time is a disk read.
            scope.launch {
                // The gate: an install that has never published a task cannot own the one just
                // ticked, and answering that from the database would open it for nothing.
                if (!prefs.hasPublishedTasks) return@launch
                // The round finds the completion for itself — including the case where the task was
                // carried into a new week under a new id, which the announcement's id alone could
                // not resolve.
                publisher.round()
            }
        }
    }

    companion object {

        @Volatile
        private var instance: ProjectApp? = null

        fun install(app: Application): ProjectApp =
            instance ?: synchronized(this) {
                instance ?: ProjectApp(app).also { it.listenForCompletions(); instance = it }
            }

        fun get(context: Context): ProjectApp =
            instance ?: synchronized(this) {
                instance ?: ProjectApp(context.applicationContext as Application)
                    .also { it.listenForCompletions(); instance = it }
            }
    }
}
