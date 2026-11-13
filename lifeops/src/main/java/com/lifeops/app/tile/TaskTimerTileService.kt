package com.lifeops.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.MainActivity
import com.lifeops.app.util.TimerLiveUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for the LifeOps task timer. While a timer runs the tile is on and shows the
 * task and its minutes; a tap stops it and logs the time, as the notification's Stop & log does.
 * With no timer running a tap opens LifeOps, where timers are started from a task.
 *
 * It can't start a timer itself: which task to time is a choice made on the task, and guessing it
 * from a tile would log hours against the wrong one.
 */
class TaskTimerTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    /** Title of the timed task, read once per task. */
    private var titleFor: String? = null
    private var title: String? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = LifeOpsApp.getOrNull() ?: return render(null, 0)
        listening?.cancel()
        listening = scope.launch {
            combine(
                app.timerController.activeTimer,
                app.timerController.elapsedSeconds.map { it / 60 }.distinctUntilChanged()
            ) { timer, _ -> timer }.collect { timer ->
                if (timer != null && titleFor != timer.taskId) {
                    titleFor = timer.taskId
                    title = runCatching { app.taskRepository.getById(timer.taskId)?.title }.getOrNull()
                }
                render(app, app.timerController.elapsedSeconds.value)
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val app = LifeOpsApp.getOrNull()
        if (app?.timerController?.activeTimer?.value != null) {
            app.timerController.stop(saveEntry = true)
            render(app, 0)
        } else {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
    }

    private fun render(app: LifeOpsApp?, elapsedSeconds: Int) {
        val tile = qsTile ?: return
        val timer = app?.timerController?.activeTimer?.value
        if (timer == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Task timer"
            tile.subtitle = "Not running"
            tile.stateDescription = "No timer running"
        } else {
            val shown = TimerLiveUpdate.display(timer, title.takeIf { titleFor == timer.taskId }, elapsedSeconds)
            tile.state = Tile.STATE_ACTIVE
            tile.label = shown.title
            tile.subtitle = shown.short
            tile.stateDescription = "Timing ${shown.title}, ${shown.short}. Tap to stop and log."
        }
        tile.updateTile()
    }
}
