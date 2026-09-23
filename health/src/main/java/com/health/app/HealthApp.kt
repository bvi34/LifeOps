package com.health.app

import android.app.Application
import android.content.Context
import com.health.app.connect.HealthConnectImporter
import com.health.app.connect.HealthConnectSyncWorker
import com.health.app.data.db.HealthDatabase
import com.health.app.data.prefs.HealthPrefs
import com.health.app.data.repository.HealthRepository
import com.health.app.data.net.DrugLookupClient
import com.health.app.data.net.ProviderDirectoryClient
import com.health.app.data.repository.HealthSyncService
import com.health.app.data.store.CardImageStore
import com.health.app.data.store.DocumentStore
import com.health.app.reminder.MedicationReminderScheduler
import com.health.app.shelf.HealthDocumentSource
import com.repository.app.source.DocumentSources
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
 * Health up is essentially free until its screen is opened.
 *
 * The two things it schedules are medication reminders, and only ones the user set — the repository
 * calls [rearmReminder] after an edit that moves one — and the Health Connect import, only while the
 * household has it switched on. Health still watches no sensors and wakes the device for nothing
 * else.
 */
class HealthApp private constructor(private val app: Application) {

    /** Tied to the process lifetime — not leaked. Carries the background sync rounds. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { HealthDatabase.getInstance(app) }
    val prefs by lazy { HealthPrefs(app) }

    /**
     * The medicine cabinet's drug lookup. Lazy like everything else here, and idle by construction:
     * it holds no connection and starts no work until a screen calls it because somebody pressed
     * Search. See [DrugLookupClient] for what does and does not cross the wire.
     */
    val drugLookup by lazy { DrugLookupClient() }

    /**
     * The Care tab's provider-directory check. Lazy and idle by construction, exactly like
     * [drugLookup]: it holds no connection and starts no work until somebody presses Check. See
     * [ProviderDirectoryClient] for the three things that can cross the wire, none of which is about
     * a member of the household.
     */
    val providerDirectory by lazy { ProviderDirectoryClient() }

    /**
     * Where photographs of insurance cards live — `filesDir/insurance-cards`, beside the database
     * rather than inside it. A card photo is a couple of megabytes and `health.db` is copied whole by
     * every backup; see [CardImageStore].
     */
    val cardImages by lazy { CardImageStore(app) }

    /**
     * Where the household's paperwork lives — `filesDir/documents`, beside the database rather than
     * inside it, for the same reason as [cardImages]. See [DocumentStore], including why a
     * photograph is re-encoded and a PDF never is.
     */
    val documents by lazy { DocumentStore(app) }

    // The seam's publish hook lives on the repository rather than in the People screen's ViewModel:
    // stamping a profile's syncVersion and telling the other peers about it are the same event, and
    // a change nobody publishes until Health next opens is a change the user goes looking for in
    // People and doesn't find.
    val repository by lazy {
        HealthRepository(
            database.healthDao(),
            prefs,
            onProfileEdit = { change -> syncPeople(rescan = change == LocalRosterChange.PERSON_ADDED) },
            onReminderChange = { medicationId -> rearmReminder(medicationId) },
            // A card photo outlives the row that pointed at it unless something deletes the file, and
            // the repository deliberately can't: it stays JVM-testable and knows nothing about disk.
            onCardImageDiscarded = { fileName -> cardImages.delete(fileName) },
            // Same hook, same reason: the repository stays JVM-testable and never touches disk.
            onDocumentDiscarded = { fileName -> documents.delete(fileName) }
        )
    }

    /**
     * Re-arm a medicine's reminder after the repository changed something that moves it — the
     * reminder being set, the medicine being paused or deleted, or a dose being recorded, which is
     * what a "when the next dose is due" reminder is measured from.
     *
     * Best-effort and off the main thread. A reminder that fails to re-queue is a missed nudge; a
     * reminder that throws while somebody is recording a 3am dose would lose the dose, and the dose
     * is the part that matters. Null means "re-arm everything", which is what a restore needs.
     */
    private fun rearmReminder(medicationId: String?) {
        scope.launch {
            runCatching {
                if (medicationId == null) {
                    MedicationReminderScheduler.rescheduleAll(app)
                } else {
                    MedicationReminderScheduler.reschedule(app, medicationId)
                }
            }
        }
    }

    /**
     * Put every reminder back on the queue. Called by the host after a restore, where the database
     * has been swapped underneath a work queue that still refers to the medicines of the database
     * that was replaced.
     */
    fun rescheduleReminders() = rearmReminder(null)

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

    /**
     * The Health Connect import — what the phone's own health store holds about the primary user.
     * Lazy and idle like everything else here: it reads nothing until the household switches it on
     * and grants permissions, and then only when Health is opened or the scheduled import runs.
     */
    val connectImporter by lazy {
        HealthConnectImporter(
            context = app,
            prefs = prefs,
            store = repository.connect,
            profileExists = { id -> database.healthDao().getProfile(id)?.archived == false }
        )
    }

    /**
     * Import from Health Connect because Health came to the foreground — at most every
     * [CONNECT_FOREGROUND_INTERVAL_MS], since walking between tabs and suite apps restarts the
     * activity far more often than anything new reaches Health Connect. [force] is the Import now
     * button, which always runs.
     */
    fun importFromHealthConnect(force: Boolean = false) {
        if (!prefs.connectEnabled) return
        if (!force && System.currentTimeMillis() - prefs.connectLastSyncAt < CONNECT_FOREGROUND_INTERVAL_MS) return
        scope.launch { runCatching { connectImporter.sync(inForeground = true) } }
    }

    /** Switch the import on or off, with the schedule that goes with it. */
    fun setConnectEnabled(enabled: Boolean) {
        prefs.connectEnabled = enabled
        if (enabled) HealthConnectSyncWorker.schedule(app) else HealthConnectSyncWorker.cancel(app)
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
        private const val CONNECT_FOREGROUND_INTERVAL_MS = 15 * 60 * 1000L

        @Volatile
        private var instance: HealthApp? = null

        fun install(app: Application): HealthApp =
            instance ?: synchronized(this) {
                instance ?: HealthApp(app).also {
                    instance = it
                    // Lend the suite's shelf what Health already holds — read-only, and lazily: the
                    // source is registered now, but nothing it wraps is touched until somebody opens
                    // Repository and the shelf asks. Health's database still costs nothing to a
                    // household that never opens Health.
                    DocumentSources.register(HealthDocumentSource(it))
                }
            }

        fun get(context: Context): HealthApp =
            instance ?: synchronized(this) {
                // Be forgiving: if the host forgot to install, build from the app context rather
                // than crash the screen.
                instance ?: HealthApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
