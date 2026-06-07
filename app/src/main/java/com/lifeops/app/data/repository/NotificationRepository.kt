package com.lifeops.app.data.repository

import android.content.Context
import androidx.work.*
import com.lifeops.app.data.db.dao.NotificationDao
import com.lifeops.app.data.db.entities.NotificationEntity
import com.lifeops.app.data.model.Task
import com.lifeops.app.util.DateUtil
import com.lifeops.app.worker.TaskNotificationWorker
import com.lifeops.app.worker.WeekCloseReminderWorker
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.TimeUnit

class NotificationRepository(
    private val context: Context,
    private val notificationDao: NotificationDao
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun scheduleForTask(task: Task) {
        val dueDate = task.dueDate ?: return
        val now = System.currentTimeMillis()

        val reminderAt = DateUtil.epochMillisForDate(dueDate, 9)
        if (reminderAt > now) {
            enqueueTaskNotification(task.id, task.title, "reminder", reminderAt - now)
            notificationDao.insert(
                NotificationEntity(UUID.randomUUID().toString(), task.id, "reminder", DateUtil.isoFromEpoch(reminderAt))
            )
        }

        if (task.hardDeadline) {
            val hardDeadlineAt = DateUtil.epochMillisForDayBefore(dueDate, 18)
            if (hardDeadlineAt > now) {
                enqueueTaskNotification(task.id, task.title, "hard_deadline", hardDeadlineAt - now)
                notificationDao.insert(
                    NotificationEntity(UUID.randomUUID().toString(), task.id, "hard_deadline", DateUtil.isoFromEpoch(hardDeadlineAt))
                )
            }
        }
    }

    fun scheduleWeekCloseReminder() {
        val now = ZonedDateTime.now()
        var nextSunday = now.with(DayOfWeek.SUNDAY).withHour(20).withMinute(0).withSecond(0).withNano(0)
        if (!nextSunday.isAfter(now)) nextSunday = nextSunday.plusWeeks(1)
        val delay = nextSunday.toInstant().toEpochMilli() - System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<WeekCloseReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag("week_close_reminder")
            .build()
        workManager.enqueueUniqueWork("week_close_reminder", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancelForTask(taskId: String) {
        workManager.cancelAllWorkByTag("task_$taskId")
        notificationDao.deleteByTask(taskId)
    }

    private fun enqueueTaskNotification(taskId: String, title: String, type: String, delayMs: Long) {
        val data = workDataOf("task_id" to taskId, "title" to title, "type" to type)
        val request = OneTimeWorkRequestBuilder<TaskNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("task_$taskId")
            .build()
        workManager.enqueue(request)
    }
}
