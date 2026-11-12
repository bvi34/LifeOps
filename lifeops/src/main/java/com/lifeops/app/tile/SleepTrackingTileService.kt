package com.lifeops.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.MainActivity

/**
 * Quick Settings toggle for LifeOps' background sleep tracking — the same switch as in Settings,
 * including starting or stopping its service.
 *
 * Starting a foreground service from a tile isn't something every Android version allows. If the
 * system refuses, the preference is put back and LifeOps' Settings open so the user can turn it on
 * there, rather than leaving a tile that says "on" over a tracker that isn't running.
 */
class SleepTrackingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val wellness = LifeOpsApp.getOrNull()?.wellnessRepository ?: return
        val turnOn = !wellness.sleepTrackingEnabled
        val started = runCatching { wellness.setSleepTrackingEnabled(turnOn) }.isSuccess
        if (!started) {
            runCatching { wellness.setSleepTrackingEnabled(false) }
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN_DESTINATION, MainActivity.DEST_SETTINGS),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = LifeOpsApp.getOrNull()?.wellnessRepository?.sleepTrackingEnabled ?: false
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Sleep tracking"
        tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
