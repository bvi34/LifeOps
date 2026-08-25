package com.health.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.health.app.data.db.HEALTH_DB_VERSION
import com.health.app.data.db.HealthDatabase
import com.health.app.data.prefs.HealthPrefs
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * Health's hook into the Operations Sandbox backup, modeled on LifeOps' and Logistics': it copies
 * the whole `health.db` — every profile, reading, symptom, medicine, dose, illness and care note —
 * so a "full backup" is complete by construction and stays complete as the schema grows.
 *
 * Health's preferences go with it, but only Health's: the hosted apps share one process and
 * therefore one `shared_prefs/` directory, so this contributor touches only files named `health_*`,
 * exactly as LifeOps confines itself to `lifeops*`. The two settings in there (selected person,
 * display unit) are small but worth carrying — restoring onto a new device and finding it opens on
 * the wrong family member is a poor first impression of a restore.
 *
 * Restore is a whole-file swap of `health.db`, so a Health restart is expected afterwards — the
 * sandbox surfaces that.
 */
class HealthBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.HEALTH
    override val displayName = "Health"

    // The schema the copied health.db was written at, read from the database declaration itself so
    // it can't drift — the mistake both LifeOps and Logistics had to correct after the fact.
    override val dataVersion = HEALTH_DB_VERSION

    override fun backup(sink: BackupSink) {
        // Fold the WAL into the main db so the file copy is current and self-contained.
        runCatching {
            HealthDatabase.getInstance(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }

        healthPrefFiles().forEach { file ->
            sink.entry("$PREFS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        // Preferences first (selected person, display unit).
        val prefsDir = sharedPrefsDir().apply { mkdirs() }
        source.list().filter { it.startsWith(PREFS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(PREFS_PREFIX)
            // Defensive: only ever write Health-owned pref files.
            if (name.startsWith(PREFS_NAME_PREFIX)) {
                source.open(rel)?.use { input -> File(prefsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

        // Then swap the database file wholesale. Close the live handle, drop stale WAL/SHM sidecars
        // (which could otherwise shadow the restored file), and copy the archived db in its place.
        source.open(DB_ENTRY)?.use { input ->
            HealthDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    /** Health's own preference XML files, isolated from the other hosted apps by name prefix. */
    private fun healthPrefFiles(): List<File> =
        sharedPrefsDir()
            .listFiles { f -> f.isFile && f.name.startsWith(PREFS_NAME_PREFIX) && f.name.endsWith(".xml") }
            ?.toList().orEmpty()

    companion object {
        private const val DB_NAME = HealthDatabase.DB_NAME
        private const val DB_ENTRY = "health.db"
        private const val PREFS_PREFIX = "shared_prefs/"

        /** Matches [HealthPrefs.FILE_NAME] and anything Health adds later under the same prefix. */
        private const val PREFS_NAME_PREFIX = "health"
    }
}
