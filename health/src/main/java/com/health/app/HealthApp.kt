package com.health.app

import android.app.Application
import android.content.Context
import com.health.app.data.db.HealthDatabase
import com.health.app.data.prefs.HealthPrefs
import com.health.app.data.repository.HealthRepository
import com.health.app.data.repository.HealthSyncService
import com.people.app.PeopleApp
import com.people.app.sync.LocalRosterChange
import com.people.app.sync.Peers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Health's tiny runtime container, mirroring LifeOps/Citation/Logistics: the hosting Operations
 * Sandbox [Application] calls [install] once, and the (single) activity resolves it with [get]. It
 * owns the Health database, its two preferences, and the repository. Everything is lazy, so bringing
 * Health up is essentially free until its screen is opened — nothing here schedules work, watches
 * sensors, or wakes the device.
 */
class HealthApp private constructor(private val app: Application) {

    /** Tied to the process lifetime — not leaked. Carries the background sync rounds. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { HealthDatabase.getInstance(app) }
    val prefs by lazy { HealthPrefs(app) }

    // The seam's publish hook lives on the repository rather than in the People screen's ViewModel:
    // stamping a profile's syncVersion and telling the other peers about it are the same event, and
    // a change nobody publishes until Health next opens is a change the user goes looking for in
    // People and doesn't find.
    val repository by lazy {
        HealthRepository(
            database.healthDao(),
            prefs,
            onProfileEdit = { change -> syncPeople(rescan = change == LocalRosterChange.PERSON_ADDED) }
        )
    }

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

    /**
     * Run a People-seam round in the background, best-effort.
     *
     * Called when Health comes to the foreground (see `MainActivity.onStart`) and after every local
     * profile edit. Both matter and neither is enough alone: the foreground round is what brings a
     * birth date over from People, and the edit round is what stops a profile added here from
     * sitting unpublished until the next time somebody happens to open Health.
     *
     * Cheap to call often — a round with nothing to do is a couple of file reads, and
     * [HealthSyncService] serializes rounds so overlapping calls queue rather than race.
     *
     * [rescan] rewinds the cursors first, and a profile added here sets it: the packet that binds
     * that profile to the directory's record of the same person — and carries their birth date over
     * — is behind the cursor, and without a rewind Health would keep a second, empty copy of them
     * for ever.
     */
    fun syncPeople(rescan: Boolean = false) {
        scope.launch { runCatching { syncService.sync(peers, rescan) } }
    }

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
