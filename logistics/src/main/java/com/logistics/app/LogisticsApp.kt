package com.logistics.app

import android.app.Application
import android.content.Context
import com.logistics.app.data.db.LogisticsDatabase
import com.logistics.app.data.prefs.LogisticsPrefs
import com.logistics.app.data.repository.LifeOpsCatalog
import com.logistics.app.data.repository.PantryRepository

/**
 * Logistics' tiny runtime container, mirroring LifeOps/Citation: the hosting Operations Sandbox
 * [Application] calls [install] once, and the (single) activity resolves it with [get]. It owns the
 * Logistics database, the [LifeOpsCatalog] bridge, the [PantryRepository], and the [LogisticsPrefs]
 * display settings. Everything is lazy, so bringing Logistics up is essentially free until its
 * screen is opened.
 */
class LogisticsApp private constructor(private val app: Application) {

    val database by lazy { LogisticsDatabase.getInstance(app) }
    val catalog by lazy { LifeOpsCatalog.create(app) }
    val pantryRepository by lazy { PantryRepository(database.pantryDao(), catalog) }
    val prefs by lazy { LogisticsPrefs(app) }

    companion object {
        @Volatile
        private var instance: LogisticsApp? = null

        fun install(app: Application): LogisticsApp =
            instance ?: synchronized(this) {
                instance ?: LogisticsApp(app).also { instance = it }
            }

        fun get(context: Context): LogisticsApp =
            instance ?: synchronized(this) {
                // Be forgiving: if the host forgot to install, build from the app context rather
                // than crash the screen. (LifeOps' catalog only needs a context.)
                instance ?: LogisticsApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
