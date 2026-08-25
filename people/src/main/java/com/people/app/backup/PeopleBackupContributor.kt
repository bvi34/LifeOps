package com.people.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import com.people.app.data.db.PEOPLE_DB_VERSION
import com.people.app.data.db.PeopleDatabase
import java.io.File

/**
 * People's hook into the Operations Sandbox backup: the whole `people.db` plus People's own
 * `people_*` preferences, which is where the sync cursors live.
 *
 * The cursors are carried on purpose, and it is worth saying why, because "restore the data, not the
 * bookkeeping" is the more obvious instinct. A restored device with its cursors reset to zero would
 * re-take every packet still sitting in the peers' envelopes — harmless in the sense that the merge
 * is idempotent, but it would resurrect people that had since been archived on this side, because
 * their old packets would arrive looking newer than nothing. Restoring the cursor with the rows
 * keeps the seam where the rows left it.
 *
 * Restore is a whole-file swap, so a People restart is expected afterwards — the sandbox surfaces
 * that.
 */
class PeopleBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.PEOPLE
    override val displayName = "People"

    override val dataVersion = PEOPLE_DB_VERSION

    override fun backup(sink: BackupSink) {
        runCatching {
            PeopleDatabase.getInstance(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }

        peoplePrefFiles().forEach { file ->
            sink.entry("$PREFS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        val prefsDir = sharedPrefsDir().apply { mkdirs() }
        source.list().filter { it.startsWith(PREFS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(PREFS_PREFIX)
            // Defensive: only ever write People-owned pref files.
            if (name.startsWith(PREFS_NAME_PREFIX)) {
                source.open(rel)?.use { input -> File(prefsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

        source.open(DB_ENTRY)?.use { input ->
            PeopleDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun peoplePrefFiles(): List<File> =
        sharedPrefsDir()
            .listFiles { f -> f.isFile && f.name.startsWith(PREFS_NAME_PREFIX) && f.name.endsWith(".xml") }
            ?.toList().orEmpty()

    companion object {
        private const val DB_NAME = PeopleDatabase.DB_NAME
        private const val DB_ENTRY = "people.db"
        private const val PREFS_PREFIX = "shared_prefs/"
        private const val PREFS_NAME_PREFIX = "people"
    }
}
