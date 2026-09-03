package com.repository.app

import android.app.Application
import android.content.Context
import com.repository.app.data.db.RepositoryDatabase
import com.repository.app.data.prefs.RepositoryPrefs
import com.repository.app.data.repository.DocumentRepository
import com.repository.app.data.store.DocumentFiles
import com.repository.app.data.store.DriveTransfer

/**
 * Repository's tiny runtime container, mirroring the other hosted apps: the Operations Sandbox
 * [Application] calls [install] once, and everything that wants the shelf resolves it with [get].
 *
 * Everything is lazy, so a household that never files a document pays nothing for the app — no
 * database file is created until something asks the shelf a question. There is no background work
 * here at all: Repository has nothing to sync, nothing to poll and nothing to remind anybody of. It
 * holds files.
 *
 * This is also the seam other apps reach through. Maintenance asks for [documents] and files against
 * an asset; Health hands the shelf a `source/DocumentSource` and keeps its own. Neither needs a
 * screen of this app to have been opened.
 */
class RepositoryApp private constructor(private val app: Application) {

    val database by lazy { RepositoryDatabase.getInstance(app) }
    val files by lazy { DocumentFiles(app) }
    val documents by lazy { DocumentRepository(database.repositoryDao(), files) }

    /** The picker's side of a drive: describing what was picked, and writing a copy back out. */
    val drives by lazy { DriveTransfer(app, files) }

    /** Which drive, and where on it — a starting point, never a store of anything. */
    val prefs by lazy { RepositoryPrefs(app) }

    companion object {

        @Volatile
        private var instance: RepositoryApp? = null

        fun install(app: Application): RepositoryApp =
            instance ?: synchronized(this) { instance ?: RepositoryApp(app).also { instance = it } }

        fun get(context: Context): RepositoryApp =
            instance ?: synchronized(this) {
                instance ?: RepositoryApp(context.applicationContext as Application).also { instance = it }
            }
    }
}
