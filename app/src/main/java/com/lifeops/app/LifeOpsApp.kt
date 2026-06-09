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
            notificationRepository
        )
    }
    val importRepository by lazy {
        ImportRepository(database, aspectRepository, taskRepository, weekRepository, notificationRepository, taskNoteRepository, timeEntryRepository)
    }
    val backupRepository by lazy { BackupRepository(database) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            val previousWeek = weekRepository.getMostRecentClosedWeek()
            val currentWeek = weekRepository.getOrCreateCurrentWeek()
            // Seed recurring tasks from the most recently closed week into the new week
            if (previousWeek != null) {
                taskRepository.seedRecurringTasks(previousWeek.id, currentWeek.id)
            }
            gameResourceRepository.ensureDefaultSlots()
            notificationRepository.scheduleWeekCloseReminder()
        }
    }
}
