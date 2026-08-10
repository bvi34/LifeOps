package com.advisor.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.advisor.app.data.db.AdvisorDatabase
import com.advisor.app.data.identity.IdentityStore
import com.advisor.app.data.memory.AdvisorMemoryDatabase
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * Advisor's hook into the Operations Sandbox backup. It captures everything Advisor owns:
 *  - `advisor.db` — granted per-app permissions + saved conversation,
 *  - `advisor_memory.db` — the dedicated, tagged long-term memory store,
 *  - `identity.json` — the identity-based data.
 *
 * Advisor does not own the knowledge it reasons over (that lives in the other apps and is backed up
 * by their contributors), so nothing else is included. Restore swaps the two database files and
 * rewrites the identity file, so an Advisor restart is expected afterwards (the sandbox surfaces it).
 */
class AdvisorBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.ADVISOR
    override val displayName = "Advisor"

    // v2 added the long-term memory database and the identity JSON. v1 was advisor.db alone.
    override val dataVersion = 2

    override fun backup(sink: BackupSink) {
        checkpoint(AdvisorDatabase.getInstance(context).query(WAL))
        checkpoint(AdvisorMemoryDatabase.getInstance(context).query(WAL))

        copyOut(sink, context.getDatabasePath(AdvisorDatabase.DB_NAME), DB_ENTRY)
        copyOut(sink, context.getDatabasePath(AdvisorMemoryDatabase.DB_NAME), MEMORY_ENTRY)

        val identity = IdentityStore.file(context)
        if (identity.exists()) {
            sink.entry(IDENTITY_ENTRY).use { out -> identity.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        source.open(DB_ENTRY)?.use { input ->
            AdvisorDatabase.closeInstance()
            overwriteDatabase(context.getDatabasePath(AdvisorDatabase.DB_NAME), input)
        }
        source.open(MEMORY_ENTRY)?.use { input ->
            AdvisorMemoryDatabase.closeInstance()
            overwriteDatabase(context.getDatabasePath(AdvisorMemoryDatabase.DB_NAME), input)
        }
        source.open(IDENTITY_ENTRY)?.use { input ->
            val file = IdentityStore.file(context)
            file.parentFile?.mkdirs()
            file.outputStream().use { input.copyTo(it) }
        }
    }

    private fun checkpoint(cursor: android.database.Cursor) {
        runCatching { cursor.use { it.moveToFirst() } }
    }

    private fun copyOut(sink: BackupSink, dbFile: File, entry: String) {
        if (dbFile.exists()) {
            sink.entry(entry).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }
    }

    private fun overwriteDatabase(dbFile: File, input: java.io.InputStream) {
        dbFile.parentFile?.mkdirs()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        dbFile.outputStream().use { input.copyTo(it) }
    }

    private companion object {
        val WAL = SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")
        const val DB_ENTRY = "advisor.db"
        const val MEMORY_ENTRY = "advisor_memory.db"
        const val IDENTITY_ENTRY = "identity.json"
    }
}
