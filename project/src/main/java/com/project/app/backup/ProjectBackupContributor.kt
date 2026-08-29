package com.project.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import com.project.app.data.db.PROJECT_DB_VERSION
import com.project.app.data.db.ProjectDatabase
import java.io.File

/**
 * Project's hook into the Operations Sandbox backup: the whole `project.db` plus Project's own
 * `project_*` preferences.
 *
 * The database is copied as a file rather than exported as JSON, and that matters more here than
 * for most of the suite. A project's documents are the only copy of writing that may exist
 * anywhere — there is no cloud workspace holding a second one — so the backup has to be the bytes,
 * not a re-serialisation that a future schema change could quietly narrow. A WAL checkpoint runs
 * first so the copied file is the whole database and not a stale main file beside a log holding
 * this morning's chapter.
 *
 * Restore is a whole-file swap, so a Project restart is expected afterwards — the sandbox surfaces
 * that.
 */
class ProjectBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.PROJECT
    override val displayName = "Project"

    override val dataVersion = PROJECT_DB_VERSION

    override fun backup(sink: BackupSink) {
        runCatching {
            ProjectDatabase.getInstance(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }

        projectPrefFiles().forEach { file ->
            sink.entry("$PREFS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        val prefsDir = sharedPrefsDir().apply { mkdirs() }
        source.list().filter { it.startsWith(PREFS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(PREFS_PREFIX)
            // Defensive: only ever write Project-owned pref files.
            if (name.startsWith(PREFS_NAME_PREFIX)) {
                source.open(rel)?.use { input -> File(prefsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

        source.open(DB_ENTRY)?.use { input ->
            ProjectDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun projectPrefFiles(): List<File> =
        sharedPrefsDir()
            .listFiles { f -> f.isFile && f.name.startsWith(PREFS_NAME_PREFIX) && f.name.endsWith(".xml") }
            ?.toList().orEmpty()

    companion object {
        private const val DB_NAME = ProjectDatabase.DB_NAME
        private const val DB_ENTRY = "project.db"
        private const val PREFS_PREFIX = "shared_prefs/"
        private const val PREFS_NAME_PREFIX = "project"
    }
}
