package com.logistics.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.logistics.app.data.db.LOGISTICS_DB_VERSION
import com.logistics.app.data.db.LogisticsDatabase
import com.logistics.app.data.store.RecipeShotStore
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
 * Logistics reads its foods and recipes from LifeOps' database and does not own that data, so the
 * catalog itself is LifeOps' contributor's job. What *is* Logistics' own and lives outside the
 * database goes with it: the **recipe screenshots** in `filesDir/recipe-shots` (see
 * [RecipeShotStore]), a megabyte or two each with only the file name on the row. A backup that
 * carried the row and not the picture would restore a recipe that claims a screenshot and hasn't
 * got one — worse than not backing it up at all, because the app would look like it had it. So the
 * directory is copied entry by entry, and restored the same way, exactly as Health carries its
 * cards. `logistics_prefs`
 * (see [com.logistics.app.data.prefs.LogisticsPrefs]) is deliberately left out too: it holds display
 * settings — how the shelf is shown, not what's on it — and restoring a backup shouldn't drag the
 * reader's screen state along with the stock. Anything in there that becomes real data would be
 * added here the same isolated way LifeOps handles its prefs.
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

        // The screenshots the recipe rows only name. See the note above.
        RecipeShotStore(context).allFiles().forEach { file ->
            sink.entry("$SHOTS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        // The pictures first, before the database that names them — so that the moment the rows
        // arrive, every file they point at is already on disk. The other order leaves a window in
        // which a recipe exists and the screenshot it claims doesn't.
        //
        // An archive entry is not allowed to name a path outside the directory. The archive is
        // Logistics' own and normally trustworthy, but a restore writes files wherever it is told
        // to, and a check that costs nothing is worth having on that one code path.
        val shotsDir = File(context.filesDir, RecipeShotStore.DIR_NAME).apply { mkdirs() }
        source.list().filter { it.startsWith(SHOTS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(SHOTS_PREFIX)
            if (name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..")) {
                source.open(rel)?.use { input -> File(shotsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

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

        /** Where the recipe screenshots sit in the archive, mirroring their directory on disk. */
        private const val SHOTS_PREFIX = "${RecipeShotStore.DIR_NAME}/"
    }
}
