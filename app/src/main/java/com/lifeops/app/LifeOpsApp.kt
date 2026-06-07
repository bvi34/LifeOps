package com.lifeops.app

import android.app.Application
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LifeOpsApp : Application() {
    val database by lazy { LifeOpsDatabase.getInstance(this) }

    val aspectRepository by lazy {
        AspectRepository(database.aspectDao(), database.categoryDao())
    }
    val weekRepository by lazy {
        WeekRepository(database.weekDao(), database.weekSnapshotDao())
    }
    val gameResourceRepository by lazy {
        GameResourceRepository(database.gameResourceDao(), database.gameResourceMappingDao())
    }
    val notificationRepository by lazy {
        NotificationRepository(this, database.notificationDao())
    }
    val taskRepository by lazy {
        TaskRepository(
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
        ImportRepository(aspectRepository, taskRepository, weekRepository, notificationRepository)
    }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            weekRepository.getOrCreateCurrentWeek()
            gameResourceRepository.ensureDefaultSlots()
            notificationRepository.scheduleWeekCloseReminder()
        }
    }
}
