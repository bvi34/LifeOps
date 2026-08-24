package com.logistics.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.logistics.app.data.db.LOGISTICS_DB_VERSION
import com.logistics.app.data.db.LogisticsDatabase
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * Logistics' hook into the Operations Sandbox backup, modeled on LifeOps': it copies the whole
 * `logistics.db` (pantry items, the movement ledger, import batches) so a "full backup" is complete
 * by construction and stays complete as the schema grows.
 *
 * Logistics reads its foods and recipes from LifeOps' database and does not own that data, so there
 * is nothing else to include — LifeOps' own contributor backs the catalog up. Logistics has no
 * SharedPreferences of its own yet; if it gains any (named `logistics_*`) they'd be added here the
 * same isolated way LifeOps handles its prefs.
 *
 * Restore is a whole-file swap of `logistics.db`, so a Logistics restart is expected afterwards —
 * the sandbox surfaces that.
 */
class LogisticsBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.LOGISTICS
    override val displayName = "Logistics"

    // The schema the copied logistics.db was written at, read from the database declaration itself
    // so it can't drift: this said 1 while the schema had already moved to 3.
    override val dataVersion = LOGISTICS_DB_VERSION

    override fun backup(sink: BackupSink) {
        // Fold the WAL into the main db so the file copy is current and self-contained.
        runCatching {
            LogisticsDatabase.getInstance(context)
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
            LogisticsDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    companion object {
        private const val DB_NAME = LogisticsDatabase.DB_NAME
        private const val DB_ENTRY = "logistics.db"
    }
}
