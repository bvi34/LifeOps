package com.people.app

import android.app.Application
import android.content.Context
import com.people.app.data.db.PeopleDatabase
import com.people.app.data.prefs.PartnerPrefs
import com.people.app.data.prefs.PeoplePrefs
import com.people.app.data.repository.CheckInRepository
import com.people.app.data.repository.PartnerRepository
import com.people.app.data.repository.PartnerSyncService
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

    /**
     * The daily check-in's store. Local to People by design — a person's day is not a fact the
     * directory seam carries, and nothing else in the suite reads these tables.
     */
    val checkInRepository by lazy { CheckInRepository(database.checkInDao()) }

    val partnerPrefs by lazy { PartnerPrefs(app) }
    val partnerRepository by lazy { PartnerRepository(database.partnerDao()) }

    val syncDir: File get() = File(app.filesDir, SYNC_DIR)

    /**
     * The partner seam's exchange folder — a sibling of [syncDir], and deliberately not the same
     * one.
     *
     * The People seam's folder is an *internal* seam: every file in it was written by an app in this
     * install, and every peer reading it is trusted. This folder holds envelopes from other
     * households. Keeping them apart means the two seams can never read each other's files by
     * accident, and a household that shares this directory to move partner envelopes between devices
     * is not thereby sharing its own directory's sync traffic.
     */
    val partnerSyncDir: File get() = File(app.filesDir, PARTNER_SYNC_DIR)

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

    /**
     * The partner seam's driver.
     *
     * Separate from [syncService] because the two seams answer to different rules — one reconciles
     * trusted apps in this install, the other exchanges a week with a stranger's device behind a
     * pairing check — and folding them into one service would be the first step towards a partner's
     * envelope being applied by code written for a peer's.
     */
    val partnerSyncService by lazy {
        PartnerSyncService(
            repository = partnerRepository,
            syncDir = partnerSyncDir,
            instanceId = { partnerPrefs.instanceId },
            displayName = { partnerPrefs.displayName },
            markRoundAt = { partnerPrefs.lastRoundAt = it }
        )
    }

    companion object {
        const val SYNC_DIR = "people-sync"
        const val PARTNER_SYNC_DIR = "partner-sync"

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
