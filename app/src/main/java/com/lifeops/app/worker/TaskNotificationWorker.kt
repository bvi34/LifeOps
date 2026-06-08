package com.lifeops.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lifeops.app.R

class TaskNotificationWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.failure()
        val title = inputData.getString("title") ?: return Result.failure()
        val type = inputData.getString("type") ?: "reminder"

        val channelId = "lifeops_tasks"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Task Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(channel)

        val isHardDeadline = type == "hard_deadline" || type == "hard_deadline_today"
        val notifTitle = when (type) {
            "hard_deadline" -> "Hard Deadline Tomorrow"
            "hard_deadline_today" -> "Hard Deadline Today"
            else -> "Task Reminder"
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notifTitle)
            .setContentText(title)
            .setPriority(if (isHardDeadline) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        nm.notify(taskId.hashCode(), notification)
        return Result.success()
    }
}
