package com.health.app

import android.app.Application
import android.content.Context
import com.health.app.data.db.HealthDatabase
import com.health.app.data.prefs.HealthPrefs
import com.health.app.data.repository.HealthRepository

/**
 * Health's tiny runtime container, mirroring LifeOps/Citation/Logistics: the hosting Operations
 * Sandbox [Application] calls [install] once, and the (single) activity resolves it with [get]. It
 * owns the Health database, its two preferences, and the repository. Everything is lazy, so bringing
 * Health up is essentially free until its screen is opened — nothing here schedules work, watches
 * sensors, or wakes the device.
 */
class HealthApp private constructor(private val app: Application) {

    val database by lazy { HealthDatabase.getInstance(app) }
    val prefs by lazy { HealthPrefs(app) }
    val repository by lazy { HealthRepository(database.healthDao(), prefs) }

    companion object {
        @Volatile
        private var instance: HealthApp? = null

        fun install(app: Application): HealthApp =
            instance ?: synchronized(this) {
                instance ?: HealthApp(app).also { instance = it }
            }

        fun get(context: Context): HealthApp =
            instance ?: synchronized(this) {
                // Be forgiving: if the host forgot to install, build from the app context rather
                // than crash the screen.
                instance ?: HealthApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
