package com.health.app

import android.app.Application
import android.content.Context
import com.health.app.data.db.HealthDatabase
import com.health.app.data.prefs.HealthPrefs
import com.health.app.data.repository.HealthRepository
import com.health.app.data.repository.HealthSyncService
import com.people.app.PeopleApp
import com.people.app.sync.Peers
import java.io.File

/**
 * Health's tiny runtime container, mirroring LifeOps/Citation/Logistics: the hosting Operations
 * Sandbox [Application] calls [install] once, and the (single) activity resolves it with [get]. It
 * owns the Health database, its two preferences, and the repository. Everything is lazy, so bringing
 * Health up is essentially free until its screen is opened — nothing here schedules work, watches
 * sensors, or wakes the device.
 */
class HealthApp private constructor(private val app: Application) {

    val database by lazy { HealthDatabase.getInstance(app) }
    val prefs by lazy { HealthPrefs(app) }
    val repository by lazy { HealthRepository(database.healthDao(), prefs) }

    /**
     * Health's side of the People sync seam. The folder is People's — `filesDir/people-sync` — so
     * all three peers reconcile in one place rather than each inventing its own mailbox.
     */
    val syncService by lazy {
        HealthSyncService(
            repository = repository,
            syncDir = File(app.filesDir, PeopleApp.SYNC_DIR),
            readCursor = { peer -> prefs.syncCursor(peer) },
            writeCursor = { peer, version -> prefs.setSyncCursor(peer, version) }
        )
    }

    /** The peers Health reconciles with. It binds to people they hold; it never creates from them. */
    val peers: List<String> = listOf(Peers.PEOPLE, Peers.LIFEOPS)

    companion object {
        @Volatile
        private var instance: HealthApp? = null

        fun install(app: Application): HealthApp =
            instance ?: synchronized(this) {
                instance ?: HealthApp(app).also { instance = it }
            }

        fun get(context: Context): HealthApp =
            instance ?: synchronized(this) {
                // Be forgiving: if the host forgot to install, build from the app context rather
                // than crash the screen.
                instance ?: HealthApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
