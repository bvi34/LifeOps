package com.lifeops.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.service.SleepTrackingService

/**
 * Re-starts the sleep-tracking foreground service after a reboot (the process — and its runtime
 * screen receiver — don't survive a restart on their own). Manifest-declared for ACTION_BOOT_COMPLETED;
 * gated on the user's tracking preference so a disabled tracker stays off.
 */
class SleepBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = LifeOpsApp.getOrNull() ?: return
        if (app.preferencesRepository.sleepTrackingEnabled) {
            SleepTrackingService.start(context)
        }
    }
}
