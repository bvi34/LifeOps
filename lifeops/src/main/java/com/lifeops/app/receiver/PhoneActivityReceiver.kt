package com.lifeops.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lifeops.app.LifeOpsApp
import com.lifeops.app.data.model.PhoneActivityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Captures the raw device signals sleep reconstruction is built from and persists each one the moment
 * it fires. Screen on/off broadcasts are only delivered to a *runtime*-registered receiver in a live
 * process, so this is registered by [com.lifeops.app.service.SleepTrackingService] (which keeps the
 * process alive overnight) rather than declared in the manifest. Charging connect/disconnect are
 * registered on the same instance for one code path.
 */
class PhoneActivityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> PhoneActivityType.SCREEN_ON
            Intent.ACTION_SCREEN_OFF -> PhoneActivityType.SCREEN_OFF
            Intent.ACTION_POWER_CONNECTED -> PhoneActivityType.CHARGING_START
            Intent.ACTION_POWER_DISCONNECTED -> PhoneActivityType.CHARGING_STOP
            else -> return
        }
        val at = System.currentTimeMillis()
        val app = LifeOpsApp.getOrNull() ?: return
        // Persist off the main thread; goAsync keeps the receiver alive until the write finishes.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.phoneActivityRepository.record(type, at)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** The intent actions this receiver handles; registered together by the tracking service. */
        val ACTIONS = listOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED
        )
    }
}
