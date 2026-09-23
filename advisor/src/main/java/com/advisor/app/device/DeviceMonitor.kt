package com.advisor.app.device

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.advisor.app.logic.DeviceProbe
import com.advisor.app.logic.DeviceState
import com.advisor.app.logic.ThermalLevel

/**
 * Reads what the phone can spare for the model: memory, heat, power, and how this process last died.
 * Everything here is a platform query, not a `/proc` scrape — the native side still logs those for
 * the kernels' view, and this is the system's view, which is the one that decides whether the suite
 * gets killed or the phone throttles.
 *
 * All of it is plain API at the suite's floor (34), which is what made it worth writing: thermal
 * headroom and exit reasons arrived at 30, the advertised memory size at 34.
 */
class DeviceMonitor(context: Context) : DeviceProbe {

    private val app = context.applicationContext
    private val activityManager = app.getSystemService(ActivityManager::class.java)
    private val powerManager = app.getSystemService(PowerManager::class.java)
    private val batteryManager = app.getSystemService(BatteryManager::class.java)

    @Volatile private var headroom: Float? = null
    @Volatile private var headroomAt: Long = 0L

    /**
     * How the previous process ended, read once: it cannot change while this one is running. The
     * suite is a single process, so this is the whole suite's death, not Advisor's — the policy only
     * blames the model when the process was at least the model's size when it went.
     */
    private val lastLowMemoryKillRssBytes: Long? by lazy {
        runCatching {
            activityManager.getHistoricalProcessExitReasons(app.packageName, 0, 1)
                .firstOrNull()
                ?.takeIf { it.reason == ApplicationExitInfo.REASON_LOW_MEMORY }
                ?.let { exit ->
                    Log.w(TAG, "Last process exit was a low-memory kill (rss ${exit.rss / 1024} MiB, pss ${exit.pss / 1024} MiB).")
                    exit.rss * 1024L
                }
        }.getOrNull()
    }

    override fun sample(): DeviceState {
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return DeviceState(
            totalMemBytes = memory.totalMem,
            advertisedMemBytes = memory.advertisedMem,
            availMemBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            thermal = thermal(powerManager.currentThermalStatus),
            thermalHeadroom = headroom(),
            powerSave = powerManager.isPowerSaveMode,
            // Integer.MIN_VALUE is the platform's "not supported".
            batteryPercent = battery?.takeIf { it in 0..100 },
            charging = batteryManager?.isCharging ?: true,
            lastLowMemoryKillRssBytes = lastLowMemoryKillRssBytes
        )
    }

    /**
     * Calls [onChange] on the main thread whenever the thermal status moves. Returns the call that
     * stops it. This is how a generation already running learns the phone has got too hot — a budget
     * is only read at the start of a turn, and a turn can last a minute.
     */
    fun watchThermal(onChange: (ThermalLevel) -> Unit): () -> Unit {
        val listener = PowerManager.OnThermalStatusChangedListener { onChange(thermal(it)) }
        powerManager.addThermalStatusListener(app.mainExecutor, listener)
        return { powerManager.removeThermalStatusListener(listener) }
    }

    /**
     * The platform rate-limits this (a second call within about a second returns NaN) and most devices
     * without a thermal HAL always answer NaN. A recent good reading stands in for a throttled one;
     * past [HEADROOM_TTL_MS] it is too old to say anything and the answer is "unknown".
     */
    private fun headroom(): Float? {
        val now = SystemClock.elapsedRealtime()
        val reading = runCatching { powerManager.getThermalHeadroom(FORECAST_SECONDS) }.getOrDefault(Float.NaN)
        if (!reading.isNaN()) {
            headroom = reading
            headroomAt = now
            return reading
        }
        return headroom?.takeIf { now - headroomAt <= HEADROOM_TTL_MS }
    }

    private fun thermal(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN
        else -> ThermalLevel.NONE
    }

    private companion object {
        const val TAG = "AdvisorDevice"

        /** How far ahead to forecast: far enough to see a generation's own heat coming. */
        const val FORECAST_SECONDS = 10

        const val HEADROOM_TTL_MS = 10_000L
    }
}
