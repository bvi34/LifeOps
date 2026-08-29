package com.project.app

import android.app.Application
import android.content.Context
import com.project.app.data.db.ProjectDatabase
import com.project.app.data.prefs.ProjectPrefs
import com.project.app.data.repository.ProjectRepository

/**
 * Project's tiny runtime container, mirroring the other hosted apps: the Operations Sandbox
 * [Application] calls [install] once, and the activity resolves it with [get].
 *
 * Everything is lazy, so a suite install where nobody ever opens Project pays nothing for it — no
 * database file is created until the shelf is first drawn. There is no sync service here and no
 * background work: Project has no peer to reconcile with and nothing to remind you about, which is
 * deliberate. Reminding you to write is LifeOps' job, and it already does it.
 */
class ProjectApp private constructor(private val app: Application) {

    val database by lazy { ProjectDatabase.getInstance(app) }
    val prefs by lazy { ProjectPrefs(app) }
    val repository by lazy { ProjectRepository(database.projectDao()) }

    companion object {

        @Volatile
        private var instance: ProjectApp? = null

        fun install(app: Application): ProjectApp =
            instance ?: synchronized(this) {
                instance ?: ProjectApp(app).also { instance = it }
            }

        fun get(context: Context): ProjectApp =
            instance ?: synchronized(this) {
                instance ?: ProjectApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
