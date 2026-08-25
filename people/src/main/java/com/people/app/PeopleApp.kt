package com.people.app

import android.app.Application
import android.content.Context
import com.people.app.data.db.PeopleDatabase
import com.people.app.data.prefs.PeoplePrefs
import com.people.app.data.repository.PeopleRepository
import com.people.app.data.repository.PeopleSyncService
import com.people.app.sync.Peers
import java.io.File

/**
 * People's tiny runtime container, mirroring the other hosted apps: the Operations Sandbox
 * [Application] calls [install] once, and the (single) activity resolves it with [get].
 *
 * [syncDir] is the shared folder the seam runs over — `filesDir/people-sync`, a sibling of the
 * `sovereign/sync` folder Citation uses. Both peers are in this one process, so "shared folder" is
 * as simple as it sounds; the point of using a folder rather than a direct call is that either side
 * can be restarted, replaced or tested on its own.
 */
class PeopleApp private constructor(private val app: Application) {

    val database by lazy { PeopleDatabase.getInstance(app) }
    val prefs by lazy { PeoplePrefs(app) }
    val repository by lazy { PeopleRepository(database.peopleDao()) }

    val syncDir: File get() = File(app.filesDir, SYNC_DIR)

    val syncService by lazy {
        PeopleSyncService(
            repository = repository,
            syncDir = syncDir,
            readCursor = { peer -> prefs.cursorFor(peer) },
            writeCursor = { peer, version -> prefs.setCursorFor(peer, version) },
            writeLastPublishedVersion = { prefs.lastPublishedVersion = it }
        )
    }

    /**
     * The peers People exchanges envelopes with.
     *
     * LifeOps holds the household outright and creates freely. Health is on the seam too but is
     * bind-only — it keeps the people it already tracks in step and never grows a medical profile
     * for one it doesn't — so People publishes to it just the same and simply hears less back.
     */
    val peers: List<String> = listOf(Peers.LIFEOPS, Peers.HEALTH)

    companion object {
        const val SYNC_DIR = "people-sync"

        @Volatile
        private var instance: PeopleApp? = null

        fun install(app: Application): PeopleApp =
            instance ?: synchronized(this) {
                instance ?: PeopleApp(app).also { instance = it }
            }

        fun get(context: Context): PeopleApp =
            instance ?: synchronized(this) {
                instance ?: PeopleApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
