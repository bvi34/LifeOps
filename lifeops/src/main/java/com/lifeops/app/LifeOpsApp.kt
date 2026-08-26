package com.lifeops.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.sqlite.db.SimpleSQLiteQuery
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.CounterEventWeather
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * LifeOps' runtime: the manual-DI holder for every repository/service plus the once-per-process
 * startup work. It used to *be* the `Application`, but under the Operations Sandbox container there
 * is a single [android.app.Application] ([com.operations.sandbox.SandboxApplication]) hosting both
 * LifeOps and Citation. So this is now a plain holder the sandbox constructs via [install]; feature
 * code reaches it through [get]/[getOrNull] instead of casting the Application.
 *
 * The class name is deliberately unchanged so existing typed references (e.g. `LifeOpsNavHost(app:
 * LifeOpsApp)`) keep compiling; only the base class and the acquisition path changed.
 */
class LifeOpsApp private constructor(private val app: Application) {
    // Tied to the process lifetime — not leaked.
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The hosting Application as a [Context], for the handful of call sites (ViewModel factories,
     * etc.) that used to receive the old `LifeOpsApp : Application` directly and just need an
     * application context.
     */
    val appContext: Context get() = app

    val database by lazy { LifeOpsDatabase.getInstance(app) }

    val aspectRepository by lazy {
        AspectRepository(database.aspectDao(), database.categoryDao())
    }
    val weekRepository by lazy {
        WeekRepository(database.weekDao(), database.weekSnapshotDao())
    }
    val gameResourceRepository by lazy {
        GameResourceRepository(
            database.gameResourceDao(),
            database.gameResourceMappingDao(),
            database.resourceTransactionDao()
        )
    }
    val gameScoreRepository by lazy { GameScoreRepository(database.gameScoreDao()) }
    val gameUnlockRepository by lazy { GameUnlockRepository(database.gameUnlockDao()) }
    val taskNoteRepository by lazy { TaskNoteRepository(database.taskNoteDao()) }
    val taskAttachmentRepository by lazy { TaskAttachmentRepository(database.taskAttachmentDao()) }
    val busyBlockRepository by lazy {
        BusyBlockRepository(
            database.busyBlockDao(),
            com.lifeops.app.worker.AlarmBusyBlockReminderScheduler(app)
        )
    }
    val googleCalendarSyncRepository by lazy {
        com.lifeops.app.data.repository.GoogleCalendarSyncRepository(app, busyBlockRepository, personRepository)
    }
    val searchRepository by lazy { SearchRepository(database) }
    val timeEntryRepository by lazy { TimeEntryRepository(database.timeEntryDao()) }
    // App-scoped so a running task timer survives navigation between This Week and the task
    // detail screen, and both observe the same clock.
    val timerController by lazy { TimerController(applicationScope, timeEntryRepository) }
    val preferencesRepository by lazy { PreferencesRepository(app) }
    val notificationRepository by lazy {
        NotificationRepository(app, database.notificationDao(), preferencesRepository)
    }
    val taskRepository by lazy {
        TaskRepository(
            database,
            database.taskDao(),
            database.aspectDao(),
            database.categoryDao(),
            database.weekDao(),
            database.weekSnapshotDao(),
            database.gameResourceDao(),
            database.gameResourceMappingDao(),
            database.notificationDao(),
            notificationRepository,
            weekRepository,
            counterRepository,
            preferencesRepository
        )
    }
    val runbookRepository by lazy { RunbookRepository(database) }
    val importRepository by lazy {
        ImportRepository(database, aspectRepository, taskRepository, weekRepository, notificationRepository, taskNoteRepository, timeEntryRepository, runbookRepository)
    }
    val templateRepository by lazy { TemplateRepository(database, importRepository) }
    val costResourceRepository by lazy {
        CostResourceRepository(database.costResourceDao(), database.taskCostEntryDao())
    }
    val backupRepository by lazy { BackupRepository(database) }
    val projectRepository by lazy { ProjectRepository(database.projectDao()) }
    // Stamps each live counter tick with the freshest cached conditions for the primary weather
    // location (cache-only, so logging never blocks on the network); yields null when there's no
    // tracked location or the cache is empty/stale, in which case the tick records no weather.
    val counterRepository by lazy {
        CounterRepository(
            database.counterDao(),
            com.lifeops.app.worker.WorkManagerHabitReminderScheduler(app)
        ) {
            weatherRepository.primaryCurrentConditions()?.let { (location, conditions) ->
                CounterEventWeather(
                    temperatureF = conditions.temperatureF,
                    feelsLikeF = conditions.feelsLikeF,
                    humidityPct = conditions.humidityPct,
                    windMph = conditions.wind.speedMph,
                    conditions = conditions.shortForecast,
                    locationName = location.name.ifBlank { null },
                    observedAt = conditions.observedAt
                )
            }
        }
    }
    val growthRepository by lazy {
        GrowthRepository(weekRepository, aspectRepository, taskRepository, timeEntryRepository)
    }
    val foodItemRepository by lazy { FoodItemRepository(database.foodItemDao()) }
    val recipeRepository by lazy { RecipeRepository(database.recipeDao(), database.foodItemDao()) }
    val foodLogRepository by lazy { FoodLogRepository(database.foodLogDao(), database.foodItemDao()) }
    val weeklyMenuRepository by lazy { WeeklyMenuRepository(database.weeklyMenuItemDao()) }
    val bookRepository by lazy { BookRepository(database.bookDao()) }
    // The LifeOps side of the Citation sync seam. Both apps share this process's filesDir, so the
    // mailbox is the folder Citation drops its outbound envelope into (filesDir/sovereign/sync).
    val citationSyncRepository by lazy {
        CitationSyncRepository(
            bookRepository,
            java.io.File(app.filesDir, "sovereign/sync"),
            readAckedVersion = { preferencesRepository.citationSyncAckedVersion },
            writeAckedVersion = { preferencesRepository.citationSyncAckedVersion = it }
        )
    }
    // The LifeOps side of the People sync seam. People and LifeOps each keep their own roster and
    // reconcile over a folder both can see (filesDir/people-sync) — the same mailbox arrangement the
    // Citation seam uses, but symmetric, because here both ends can edit the same person.
    val peopleSyncRepository by lazy {
        PeopleSyncRepository(
            personRepository,
            java.io.File(app.filesDir, com.people.app.PeopleApp.SYNC_DIR),
            readCursor = { peer -> preferencesRepository.peopleSyncCursor(peer) },
            writeCursor = { peer, version -> preferencesRepository.setPeopleSyncCursor(peer, version) }
        )
    }
    val futureProjectRepository by lazy { FutureProjectRepository(database.futureProjectDao()) }
    val weatherRepository by lazy { WeatherRepository(database.weatherDao()) }
    // The People-seam publish hook is wired here rather than at each ViewModel: a person is minted
    // in more places than the People screen (the calendar worker, the connection layer, the detail
    // editor), and every local edit has to reach the other peers, not just the ones somebody
    // remembered to add a call to.
    val personRepository by lazy {
        PersonRepository(database.personDao(), onLocalEdit = { syncPeople() })
    }
    val milestoneRepository by lazy {
        MilestoneRepository(
            database.milestoneDao(),
            database.gameResourceMappingDao(),
            database.gameResourceDao(),
            database.resourceTransactionDao()
        )
    }
    val activityTemplateRepository by lazy { ActivityTemplateRepository(database.activityTemplateDao()) }
    val phoneActivityRepository by lazy {
        PhoneActivityRepository(database.phoneActivityEventDao())
    }
    val wellnessRepository by lazy {
        WellnessRepository(app, database.wellnessCheckinDao(), phoneActivityRepository, preferencesRepository)
    }

    // --- Connection layer (in-process command dispatch) ---
    // Addresses of the form /v1/LifeOps/{connection}/{resource}/{action}; `local` is internal app
    // comms, named connections are reserved for future integrations. See package `connection`.
    val taskService by lazy {
        com.lifeops.app.connection.service.TaskService(
            taskRepository, taskNoteRepository, notificationRepository, weekRepository
        )
    }
    val weekService by lazy {
        com.lifeops.app.connection.service.WeekService(weekRepository, taskRepository)
    }
    val projectService by lazy {
        com.lifeops.app.connection.service.ProjectService(projectRepository)
    }
    val counterService by lazy {
        com.lifeops.app.connection.service.CounterService(counterRepository)
    }
    val noteService by lazy {
        com.lifeops.app.connection.service.NoteService(taskNoteRepository)
    }
    val aspectService by lazy {
        com.lifeops.app.connection.service.AspectService(aspectRepository)
    }
    val personService by lazy {
        com.lifeops.app.connection.service.PersonService(personRepository)
    }
    val busyBlockService by lazy {
        com.lifeops.app.connection.service.BusyBlockService(busyBlockRepository)
    }
    val timeEntryService by lazy {
        com.lifeops.app.connection.service.TimeEntryService(timeEntryRepository, taskRepository)
    }
    val bookService by lazy {
        com.lifeops.app.connection.service.BookService(bookRepository)
    }
    val recipeService by lazy {
        com.lifeops.app.connection.service.RecipeService(recipeRepository)
    }
    val foodService by lazy {
        com.lifeops.app.connection.service.FoodService(foodItemRepository, foodLogRepository)
    }
    val mealPlanService by lazy {
        com.lifeops.app.connection.service.MealPlanService(
            weeklyMenuRepository, recipeRepository, foodLogRepository
        )
    }
    val futureProjectService by lazy {
        com.lifeops.app.connection.service.FutureProjectService(futureProjectRepository)
    }
    val costService by lazy {
        com.lifeops.app.connection.service.CostService(costResourceRepository)
    }
    val activityService by lazy {
        com.lifeops.app.connection.service.ActivityService(activityTemplateRepository)
    }
    val runbookService by lazy {
        com.lifeops.app.connection.service.RunbookService(runbookRepository)
    }
    val wellnessService by lazy {
        com.lifeops.app.connection.service.WellnessService(wellnessRepository)
    }
    val connectionDispatcher by lazy {
        com.lifeops.app.connection.Connections.buildDispatcher(
            com.lifeops.app.connection.Connections.Services(
                task = taskService,
                week = weekService,
                project = projectService,
                counter = counterService,
                note = noteService,
                aspect = aspectService,
                person = personService,
                busyBlock = busyBlockService,
                timeEntry = timeEntryService,
                book = bookService,
                recipe = recipeService,
                food = foodService,
                mealPlan = mealPlanService,
                futureProject = futureProjectService,
                cost = costService,
                activity = activityService,
                runbook = runbookService,
                wellness = wellnessService
            )
        )
    }

    /**
     * Reconcile the household roster with People and Health, in the background, best-effort.
     *
     * This is deliberately *not* startup-only. All six suite apps share one process, so LifeOps'
     * startup runs once and then never again however many times the user walks between LifeOps,
     * People and Health — which is precisely the case where the roster falls out of step. So the
     * round runs on every LifeOps foreground (see `MainActivity.onStart`) as well as after every
     * local person edit ([PersonRepository]'s `onLocalEdit`).
     *
     * Calling it often is cheap and safe: a round with nothing to do is a couple of file reads, and
     * [PeopleSyncRepository] serializes rounds so overlapping calls queue rather than race.
     */
    fun syncPeople() {
        applicationScope.launch { runCatching { peopleSyncRepository.sync() } }
    }

    /**
     * Run LifeOps' once-per-process startup. Called by the sandbox after [install]. Idempotent at
     * the [install] level (constructed + started once); safe to treat as the old `onCreate`.
     */
    private fun start() {
        // Fold the write-ahead log back into lifeops.db whenever the app leaves the
        // foreground. Android's Auto Backup / device transfer copies the .db file only
        // (see backup_rules.xml / data_extraction_rules.xml); without this, writes still
        // sitting in the -wal sidecar would be missing from a fresh-device restore.
        app.registerActivityLifecycleCallbacks(BackgroundWalCheckpoint())
        applicationScope.launch {
            if (!preferencesRepository.sameWeekCarryRepairDone) {
                taskRepository.repairSameWeekCarries()
                preferencesRepository.sameWeekCarryRepairDone = true
            }
            if (!preferencesRepository.growthAspectHistoryBackfillDone) {
                // Non-fatal: if this fails, rings just fall back to live derivation for old
                // weeks and we retry next launch — it must never block current-week creation.
                try {
                    taskRepository.backfillAspectHistory()
                    preferencesRepository.growthAspectHistoryBackfillDone = true
                } catch (_: Exception) { }
            }
            // Seal any whole weeks that elapsed while the app was closed, before we resolve
            // the current week — otherwise a stale open week would masquerade as "this week".
            val closedUpTo = taskRepository.catchUpClose()
            if (closedUpTo != null && closedUpTo > preferencesRepository.lastClosedWeek) {
                preferencesRepository.lastClosedWeek = closedUpTo
            }
            val previousWeek = weekRepository.getMostRecentClosedWeek()
            val currentWeek = weekRepository.getOrCreateCurrentWeek()
            // Seed recurring tasks from the most recently closed week into the new week
            if (previousWeek != null) {
                taskRepository.seedRecurringTasks(previousWeek.id, currentWeek.id)
            }
            // Wake queued tasks whose due date now falls inside the current week (normally
            // handled at week close; this catches restores/imports and clock changes).
            taskRepository.activateDueQueuedTasks(currentWeek)
            gameResourceRepository.ensureDefaultSlots()
            activityTemplateRepository.ensureDefaults()
            notificationRepository.scheduleWeekCloseReminder()
            // Re-arm per-habit daily reminders from the database (WorkManager's queue can be lost
            // across reinstall/device transfer while the reminder hours persist in Room).
            counterRepository.rescheduleAllReminders()
            // Same for start-of-block reminders on the user's own busy times.
            busyBlockRepository.rescheduleAllReminders()
            // Pull in any reading telemetry / notes Citation has dropped in the shared mailbox since
            // last launch, so time entries + book notes are current before Reports/Books render.
            // Best-effort: a missing or half-written envelope must never block startup.
            runCatching { citationSyncRepository.sync() }
            // Reconcile the household roster with People and Health the same way, and for the same
            // reason: the People screen and LifeOps' own person pickers should agree before either is
            // rendered. On a first run this is also how the household LifeOps already knows about
            // reaches a freshly-installed People — the seam does the work an import step would have.
            // This is the *first* round, not the only one; see [syncPeople].
            runCatching { peopleSyncRepository.sync() }
        }
        // Keep the weather cache warm in the background (no-op-cheap when no locations exist).
        com.lifeops.app.worker.WeatherRefreshWorker.schedulePeriodic(app)
        // Daytime wellness check-in reminders at the configured slots (default 10:00/15:00/21:00).
        // Each fired slot re-schedules its own next occurrence; this seeds them from settings (and
        // clears them if the user has turned reminders off).
        wellnessRepository.rescheduleReminders()
        // Keep the background sleep tracker running so screen/charging events are captured overnight
        // (see SleepTrackingService). No-op when the user has turned tracking off.
        if (preferencesRepository.sleepTrackingEnabled) {
            runCatching { com.lifeops.app.service.SleepTrackingService.start(app) }
        }
        // Periodic Google Calendar sync, opt-in via the Calendar Sync screen. A manual "Sync now"
        // there works regardless of this toggle.
        if (preferencesRepository.googleCalendarSyncEnabled && preferencesRepository.googleCalendarId != null) {
            com.lifeops.app.worker.GoogleCalendarSyncWorker.schedulePeriodic(app)
        }
    }

    companion object {
        @Volatile
        private var instance: LifeOpsApp? = null

        /**
         * Construct the LifeOps runtime against the single hosting [app] and run its startup, once.
         * Called by the sandbox's Application. Repeated calls return the existing instance without
         * re-running startup.
         */
        fun install(app: Application): LifeOpsApp =
            instance ?: synchronized(this) {
                instance ?: LifeOpsApp(app).also { instance = it; it.start() }
            }

        /** The installed runtime. Throws if the host never called [install] (a wiring bug). */
        fun get(context: Context): LifeOpsApp =
            instance ?: error("LifeOpsApp.install() was never called by the hosting Application")

        /**
         * The installed runtime, or null if not yet installed — for background entry points
         * (workers, receivers, the widget) that must degrade gracefully rather than crash when they
         * fire before/without the host process wiring.
         */
        fun getOrNull(): LifeOpsApp? = instance
    }

    /**
     * Runs a TRUNCATE WAL checkpoint on a background coroutine once the last activity
     * stops (app backgrounded). TRUNCATE empties the -wal file into the main db, so the
     * file-based backup captures the latest state and never restores a mismatched sidecar.
     */
    private inner class BackgroundWalCheckpoint : Application.ActivityLifecycleCallbacks {
        private var startedActivities = 0

        override fun onActivityStarted(activity: Activity) {
            startedActivities++
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities--
            if (startedActivities <= 0) {
                startedActivities = 0
                applicationScope.launch {
                    runCatching {
                        database.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                            .use { it.moveToFirst() }
                    }
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
