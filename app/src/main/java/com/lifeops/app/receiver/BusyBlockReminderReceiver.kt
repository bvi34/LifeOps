package com.lifeops.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.worker.BusyBlockAlarms
import kotlinx.coroutines.launch

/**
 * Delivers busy-block start-of-block reminders. An [BusyBlockAlarms.ACTION_FIRE] alarm posts the
 * notification and re-arms the next weekly occurrence. On boot or app-update — when the OS clears all
 * pending alarms — it re-arms every own-schedule reminder from the database (the app also does this
 * on launch, but this covers reboots that happen before the app is next opened).
 */
class BusyBlockReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = context.applicationContext as? LifeOpsApp ?: return
                val pending = goAsync()
                app.applicationScope.launch {
                    try {
                        app.busyBlockRepository.rescheduleAllReminders()
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> BusyBlockAlarms.onFire(context, intent)
        }
    }
}
