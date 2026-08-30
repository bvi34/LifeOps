package com.maintenance.app

import android.app.Application
import android.content.Context
import com.lifeops.app.connection.TaskCompletionBus
import com.maintenance.app.data.db.MaintenanceDatabase
import com.maintenance.app.data.prefs.MaintenancePrefs
import com.maintenance.app.data.repository.MaintenanceRepository
import com.maintenance.app.data.repository.UpkeepPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Maintenance's tiny runtime container, mirroring the other hosted apps: the Operations Sandbox
 * [Application] calls [install] once, and the activity resolves it with [get].
 *
 * Everything is lazy, so a suite install where nobody ever opens Maintenance pays nothing for it —
 * no database file is created until the docket is first drawn. There is no sync service here and no
 * background work: Maintenance has no peer to reconcile with, and it deliberately raises no
 * notifications of its own. What it does instead is put its upkeep **on the LifeOps week** as tasks
 * dated the day they fall due, and take the ticks back (see [UpkeepPublisher]) — one planner for
 * the suite, and this app supplying it rather than competing with it.
 *
 * [install] registers for LifeOps' completion announcements. That registration is deliberately
 * cheap and deliberately gated: it is a listener on a bus, and it does nothing at all until this
 * install has actually published something, so the lazy-database promise above survives a household
 * that never opens this app.
 */
class MaintenanceApp private constructor(private val app: Application) {

    val database by lazy { MaintenanceDatabase.getInstance(app) }
    val prefs by lazy { MaintenancePrefs(app) }
    val repository by lazy { MaintenanceRepository(database.maintenanceDao()) }
    val publisher by lazy {
        UpkeepPublisher(
            repository = repository,
            onPublished = { prefs.hasPublishedTasks = true }
        )
    }

    /**
     * Rounds run here rather than on a screen's scope: a tick announced by LifeOps arrives with no
     * screen behind it, and a round started by the last thing you did in Maintenance should finish
     * even if you left immediately afterwards.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Reconcile the schedules with the week, in the background.
     *
     * Safe to call often and from anywhere — the round is idempotent, so the honest thing is to run
     * it whenever something might have changed rather than to reason about when it can be skipped.
     */
    fun syncNow() {
        scope.launch { publisher.round() }
    }

    private fun listenForCompletions() {
        TaskCompletionBus.register {
            // Straight onto the background scope, gate and all: LifeOps resumes this on whichever
            // dispatcher ticked the task, which for a tap on a task row is the main thread — and
            // even reading a preference for the first time is a disk read.
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
        private var instance: MaintenanceApp? = null

        fun install(app: Application): MaintenanceApp =
            instance ?: synchronized(this) {
                instance ?: MaintenanceApp(app).also { it.listenForCompletions(); instance = it }
            }

        fun get(context: Context): MaintenanceApp =
            instance ?: synchronized(this) {
                instance ?: MaintenanceApp(context.applicationContext as Application)
                    .also { it.listenForCompletions(); instance = it }
            }
    }
}
