package com.maintenance.app

import android.app.Application
import android.content.Context
import com.maintenance.app.data.db.MaintenanceDatabase
import com.maintenance.app.data.prefs.MaintenancePrefs
import com.maintenance.app.data.repository.MaintenanceRepository

/**
 * Maintenance's tiny runtime container, mirroring the other hosted apps: the Operations Sandbox
 * [Application] calls [install] once, and the activity resolves it with [get].
 *
 * Everything is lazy, so a suite install where nobody ever opens Maintenance pays nothing for it —
 * no database file is created until the docket is first drawn. There is no sync service here and no
 * background work: Maintenance has no peer to reconcile with, and it deliberately does not schedule
 * notifications. It knows what is due; deciding when you will get to it is LifeOps' job, and a
 * second app that pushes tasks at you is a second answer to "what am I doing today".
 */
class MaintenanceApp private constructor(private val app: Application) {

    val database by lazy { MaintenanceDatabase.getInstance(app) }
    val prefs by lazy { MaintenancePrefs(app) }
    val repository by lazy { MaintenanceRepository(database.maintenanceDao()) }

    companion object {

        @Volatile
        private var instance: MaintenanceApp? = null

        fun install(app: Application): MaintenanceApp =
            instance ?: synchronized(this) {
                instance ?: MaintenanceApp(app).also { instance = it }
            }

        fun get(context: Context): MaintenanceApp =
            instance ?: synchronized(this) {
                instance ?: MaintenanceApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
