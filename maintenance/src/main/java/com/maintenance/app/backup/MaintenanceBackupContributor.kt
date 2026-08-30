package com.maintenance.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.maintenance.app.data.db.MAINTENANCE_DB_VERSION
import com.maintenance.app.data.db.MaintenanceDatabase
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * Maintenance's hook into the Operations Sandbox backup: the whole `maintenance.db` plus
 * Maintenance's own `maintenance_*` preferences.
 *
 * What it carries now includes the recalls a vehicle has been told about and which of them this
 * household has dealt with — the second half of which exists nowhere else, since NHTSA knows what is
 * open for a model and only you know what has been done to yours.
 *
 * The database is copied as a file rather than exported as JSON. What this app holds is the only
 * copy of things that are genuinely hard to reconstruct — a VIN off a door jamb, a parcel number
 * off a tax bill, eleven years of what the furnace cost — so the backup should be the bytes, not a
 * re-serialisation that a future schema change could quietly narrow. A WAL checkpoint runs first so
 * the copied file is the whole database and not a stale main file beside a log holding this
 * morning's service record.
 *
 * Restore is a whole-file swap, so a Maintenance restart is expected afterwards — the sandbox
 * surfaces that.
 *
 * The one thing in this file that can arrive *stale* is the LifeOps task id each upkeep plan carries
 * (see `logic/UpkeepTasks`): restore Maintenance without restoring LifeOps and those ids name tasks
 * that no longer exist. Nothing here compensates for that, deliberately — the next publishing round
 * reads the week, finds them gone, and drops the links. A seam that reconciles doesn't need its
 * backup to be clever.
 */
class MaintenanceBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.MAINTENANCE
    override val displayName = "Maintenance"

    override val dataVersion = MAINTENANCE_DB_VERSION

    override fun backup(sink: BackupSink) {
        runCatching {
            MaintenanceDatabase.getInstance(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }

        maintenancePrefFiles().forEach { file ->
            sink.entry("$PREFS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        val prefsDir = sharedPrefsDir().apply { mkdirs() }
        source.list().filter { it.startsWith(PREFS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(PREFS_PREFIX)
            // Defensive: only ever write Maintenance-owned pref files.
            if (name.startsWith(PREFS_NAME_PREFIX)) {
                source.open(rel)?.use { input -> File(prefsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

        source.open(DB_ENTRY)?.use { input ->
            MaintenanceDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun maintenancePrefFiles(): List<File> =
        sharedPrefsDir()
            .listFiles { f -> f.isFile && f.name.startsWith(PREFS_NAME_PREFIX) && f.name.endsWith(".xml") }
            ?.toList().orEmpty()

    companion object {
        private const val DB_NAME = MaintenanceDatabase.DB_NAME
        private const val DB_ENTRY = "maintenance.db"
        private const val PREFS_PREFIX = "shared_prefs/"
        private const val PREFS_NAME_PREFIX = "maintenance"
    }
}
