package com.lifeops.app.data.repository

import android.content.Context
import androidx.work.*
import com.lifeops.app.data.db.dao.NotificationDao
import com.lifeops.app.data.db.entities.NotificationEntity
import com.lifeops.app.data.model.Task
import com.lifeops.app.util.DateUtil
import com.lifeops.app.worker.TaskNotificationWorker
import com.lifeops.app.worker.WeekCloseReminderWorker
import java.time.*
import java.util.*
import java.util.concurrent.TimeUnit

class NotificationRepository(
    private val context: Context,
    private val notificationDao: NotificationDao
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun scheduleForTask(task: Task) {
        val dueDate = task.dueDate ?: return
        val reminderMillis = DateUtil.epochMillisForDate(dueDate, 9)
        val now = System.currentTimeMillis()
        if (reminderMillis > now) {
            val delay = reminderMillis - now
            enqueueTaskNotification(task.id, task.title, "reminder", delay)
            val notif = NotificationEntity(
                id = UUID.randomUUID().toString(),
                taskId = task.id,
                type = "reminder",
                scheduledAt = DateUtil.isoFromEpoch(reminderMillis)
            )
            notificationDao.insert(notif)
        }
        if (task.hardDeadline) {
            val dayBefore = DateUtil.epochMillisForDate(dueDate, 18) - TimeUnit.DAYS.toMillis(1)
            if (dayBefore > now) {
                val delay = dayBefore - now
                enqueueTaskNotification(task.id, task.title, "hard_deadline", delay)
                val notif = NotificationEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = task.id,
                    type = "hard_deadline",
                    scheduledAt = DateUtil.isoFromEpoch(dayBefore)
                )
                notificationDao.insert(notif)
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

    private fun enqueueTaskNotification(taskId: String, title: String, type: String, delayMs: Long) {
        val data = workDataOf(
            "task_id" to taskId,
            "title" to title,
            "type" to type
        )
        val request = OneTimeWorkRequestBuilder<TaskNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("task_$taskId")
            .build()
        workManager.enqueue(request)
    }

    suspend fun cancelForTask(taskId: String) {
        workManager.cancelAllWorkByTag("task_$taskId")
        notificationDao.deleteByTask(taskId)
    }
}
