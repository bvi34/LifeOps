package com.lifeops.app

import android.app.Application
import com.lifeops.app.data.db.LifeOpsDatabase
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
    val taskNoteRepository by lazy { TaskNoteRepository(database.taskNoteDao()) }
    val timeEntryRepository by lazy { TimeEntryRepository(database.timeEntryDao()) }
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
            counterRepository
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
    val counterRepository by lazy { CounterRepository(database.counterDao()) }
    val growthRepository by lazy {
        GrowthRepository(weekRepository, aspectRepository, taskRepository, timeEntryRepository)
    }
    val foodItemRepository by lazy { FoodItemRepository(database.foodItemDao()) }
    val recipeRepository by lazy { RecipeRepository(database.recipeDao(), database.foodItemDao()) }
    val foodLogRepository by lazy { FoodLogRepository(database.foodLogDao(), database.foodItemDao()) }
    val bookRepository by lazy { BookRepository(database.bookDao()) }
    val futureProjectRepository by lazy { FutureProjectRepository(database.futureProjectDao()) }
    val weatherRepository by lazy { WeatherRepository(database.weatherDao()) }

    override fun onCreate() {
        super.onCreate()
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
            notificationRepository.scheduleWeekCloseReminder()
        }
    }
}
