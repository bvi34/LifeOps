package com.advisor.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.advisor.app.data.db.AdvisorDatabase
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * Advisor's hook into the Operations Sandbox backup, modeled on the others: it copies the whole
 * `advisor.db` (granted per-app permissions + saved conversation) so a "full backup" is complete by
 * construction and stays complete as the schema grows.
 *
 * Advisor does not own the knowledge it reasons over — that lives in LifeOps/Citation/Logistics and
 * is backed up by their own contributors — so there is nothing else to include here. Restore is a
 * whole-file swap of `advisor.db`, so an Advisor restart is expected afterwards (the sandbox surfaces
 * that).
 */
class AdvisorBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.ADVISOR
    override val displayName = "Advisor"

    // Matches AdvisorDatabase @Database(version = 1).
    override val dataVersion = 1

    override fun backup(sink: BackupSink) {
        // Fold the WAL into the main db so the file copy is current and self-contained.
        runCatching {
            AdvisorDatabase.getInstance(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        source.open(DB_ENTRY)?.use { input ->
            AdvisorDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    companion object {
        private const val DB_NAME = AdvisorDatabase.DB_NAME
        private const val DB_ENTRY = "advisor.db"
    }
}
