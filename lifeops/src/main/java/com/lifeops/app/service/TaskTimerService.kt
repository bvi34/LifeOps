package com.lifeops.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.TileService
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.MainActivity
import com.lifeops.app.R
import com.lifeops.app.data.repository.ActiveTimer
import com.lifeops.app.tile.TaskTimerTileService
import com.lifeops.app.util.TimerDisplay
import com.lifeops.app.util.TimerLiveUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The running task timer, outside the app. While a timer runs this foreground service keeps an
 * ongoing notification up showing the task and its clock, with a **Stop & log** button.
 *
 * On Android 16 QPR2 and later (the Pixel 10 among them) the notification asks to be a **Live
 * Update**: promoted to the top of the shade and the lock screen, with a chip in the status bar that
 * shows the clock. A Pomodoro counts down to its end and draws its 25 minutes as a progress bar; an
 * open timer counts up. Older Android shows the same notification, just not promoted.
 *
 * It is also what keeps the timer alive. [com.lifeops.app.data.repository.TimerController] holds the
 * clock in memory, so before this service a timer left running while the user did something else
 * could be lost when Android reclaimed the backgrounded process. A foreground service keeps the
 * process there until the timer stops.
 *
 * Nothing starts it but [follow], which mirrors the controller: running timer → service up; no
 * timer → service down. The service never owns timer state; it only shows it.
 */
class TaskTimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The title read for [titleFor]'s task, so a ticking update doesn't hit the database. */
    private var titleFor: String? = null
    private var title: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val app = LifeOpsApp.getOrNull() ?: return
        // Re-post when the timer changes or a minute ticks over. The chip's clock runs on its own
        // (the platform's chronometer), so per-second re-posting would only cost battery.
        scope.launch {
            combine(
                app.timerController.activeTimer,
                app.timerController.elapsedSeconds.map { it / 60 }.distinctUntilChanged()
            ) { timer, _ -> timer }.collect { timer ->
                if (timer == null) return@collect
                if (titleFor != timer.taskId) {
                    titleFor = timer.taskId
                    title = runCatching { app.taskRepository.getById(timer.taskId)?.title }.getOrNull()
                }
                notificationManager().notify(NOTIFICATION_ID, build(timer))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = LifeOpsApp.getOrNull()
        if (intent?.action == ACTION_STOP) {
            // Logging happens in the controller; [follow] sees the timer end and stops this service.
            app?.timerController?.stop(saveEntry = true)
        }
        val timer = app?.timerController?.activeTimer?.value
        if (timer == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, build(timer), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // Not sticky: the timer is in memory, so a restarted process has no timer to show.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(timer: ActiveTimer): Notification {
        val elapsed = LifeOpsApp.getOrNull()?.timerController?.elapsedSeconds?.value ?: 0
        val shown = TimerLiveUpdate.display(timer, title.takeIf { titleFor == timer.taskId }, elapsed)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_timer)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(shown.chronometerBaseMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(shown.countsDown)
            .setContentIntent(openLifeOps())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_tile_timer), "Stop & log", stopIntent()
                ).build()
            )
        applyLiveUpdate(builder, shown)
        return builder.build()
    }

    /**
     * The Android 16 parts. ProgressStyle exists from API 36; the promotion request is read from
     * API 36.1 (QPR2) and is an extra so it compiles against this project's SDK — an older Android
     * ignores it and shows an ordinary ongoing notification.
     */
    private fun applyLiveUpdate(builder: Notification.Builder, shown: TimerDisplay) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        val progress = shown.progress ?: return
        builder.setStyle(
            Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(shown.progressMax)))
                .setProgress(progress)
        )
    }

    private fun openLifeOps(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, TaskTimerService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannel() {
        // LOW, not MIN: a minimum-importance channel can't be promoted to a Live Update, and LOW is
        // still silent.
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Task timer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The running task timer, with its clock in the status bar."
                setShowBadge(false)
            }
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "lifeops_task_timer"
        private const val NOTIFICATION_ID = 88200
        private const val ACTION_STOP = "com.lifeops.app.action.STOP_TASK_TIMER"

        /** `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`, API 36.1. */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        /**
         * Mirror the timer: start this service while one runs and stop it when it ends, and refresh
         * the timer's Quick Settings tile either way. Called once, from LifeOps' startup.
         */
        fun follow(context: Context, app: LifeOpsApp) {
            val appContext = context.applicationContext
            app.applicationScope.launch {
                app.timerController.activeTimer
                    .map { it != null }
                    .distinctUntilChanged()
                    .collect { running ->
                        if (running) {
                            // A timer is only ever started from a LifeOps screen, so the app is in
                            // the foreground and may start this. Guarded all the same: failing to
                            // show the clock must never take the timer down with it.
                            runCatching {
                                appContext.startForegroundService(Intent(appContext, TaskTimerService::class.java))
                            }
                        } else {
                            appContext.stopService(Intent(appContext, TaskTimerService::class.java))
                        }
                        runCatching {
                            TileService.requestListeningState(
                                appContext, ComponentName(appContext, TaskTimerTileService::class.java)
                            )
                        }
                    }
            }
        }
    }
}
