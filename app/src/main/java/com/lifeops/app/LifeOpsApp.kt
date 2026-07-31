package com.lifeops.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.sqlite.db.SimpleSQLiteQuery
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.model.CounterEventWeather
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LifeOpsApp : Application() {
    // Tied to the process lifetime — not leaked.
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { LifeOpsDatabase.getInstance(this) }

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
            com.lifeops.app.worker.AlarmBusyBlockReminderScheduler(this)
        )
    }
    val searchRepository by lazy { SearchRepository(database) }
    val timeEntryRepository by lazy { TimeEntryRepository(database.timeEntryDao()) }
    // App-scoped so a running task timer survives navigation between This Week and the task
    // detail screen, and both observe the same clock.
    val timerController by lazy { TimerController(applicationScope, timeEntryRepository) }
    val preferencesRepository by lazy { PreferencesRepository(this) }
    val notificationRepository by lazy {
        NotificationRepository(this, database.notificationDao(), preferencesRepository)
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
            com.lifeops.app.worker.WorkManagerHabitReminderScheduler(this)
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
    val bookRepository by lazy { BookRepository(database.bookDao()) }
    val futureProjectRepository by lazy { FutureProjectRepository(database.futureProjectDao()) }
    val weatherRepository by lazy { WeatherRepository(database.weatherDao()) }
    val personRepository by lazy { PersonRepository(database.personDao()) }
    val activityTemplateRepository by lazy { ActivityTemplateRepository(database.activityTemplateDao()) }
    val phoneActivityRepository by lazy {
        PhoneActivityRepository(database.phoneActivityEventDao())
    }
    val wellnessRepository by lazy {
        WellnessRepository(this, database.wellnessCheckinDao(), phoneActivityRepository, preferencesRepository)
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
                futureProject = futureProjectService,
                cost = costService,
                activity = activityService,
                runbook = runbookService,
                wellness = wellnessService
            )
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Fold the write-ahead log back into lifeops.db whenever the app leaves the
        // foreground. Android's Auto Backup / device transfer copies the .db file only
        // (see backup_rules.xml / data_extraction_rules.xml); without this, writes still
        // sitting in the -wal sidecar would be missing from a fresh-device restore.
        registerActivityLifecycleCallbacks(BackgroundWalCheckpoint())
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
        }
        // Keep the weather cache warm in the background (no-op-cheap when no locations exist).
        com.lifeops.app.worker.WeatherRefreshWorker.schedulePeriodic(this)
        // Daytime wellness check-in reminders at the configured slots (default 10:00/15:00/21:00).
        // Each fired slot re-schedules its own next occurrence; this seeds them from settings (and
        // clears them if the user has turned reminders off).
        wellnessRepository.rescheduleReminders()
        // Keep the background sleep tracker running so screen/charging events are captured overnight
        // (see SleepTrackingService). No-op when the user has turned tracking off.
        if (preferencesRepository.sleepTrackingEnabled) {
            runCatching { com.lifeops.app.service.SleepTrackingService.start(this) }
        }
    }

    /**
     * Runs a TRUNCATE WAL checkpoint on a background coroutine once the last activity
     * stops (app backgrounded). TRUNCATE empties the -wal file into the main db, so the
     * file-based backup captures the latest state and never restores a mismatched sidecar.
     */
    private inner class BackgroundWalCheckpoint : ActivityLifecycleCallbacks {
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
