package com.finance.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.finance.app.data.db.FINANCE_DB_VERSION
import com.finance.app.data.db.FinanceDatabase
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * Finance's hook into the Operations Sandbox backup: the whole `finance.db` plus Finance's own
 * `finance_*` preferences.
 *
 * ## What is deliberately left behind
 *
 * **Every credential.** No Plaid client secret, no access token, no Mercury API token, no sync
 * cursor. Those live in `secure_finance_access` behind the Keystore, and the file's name does not
 * start with `finance`, so [financePrefFiles] cannot pick it up even by accident — the exclusion is
 * a property of the name rather than of a filter somebody could relax in a later commit.
 *
 * That is the single most important line in this file, and it used to come with a cost stated just
 * as plainly: restore onto a new phone and every connection had to be re-authorised.
 *
 * **That cost is now paid somewhere else.** Every credential this app writes is also mirrored into
 * the Secrets vault (see `FinanceSecrets`), and the vault *does* travel — as a sealed file whose key
 * is a passphrase in somebody's head rather than anything the archive or the phone holds. So the
 * archive still contains no readable token, this contributor still writes none, and a restore onto a
 * new phone gets its connections back the moment somebody unlocks their vault. If there is no vault,
 * the old bargain stands unchanged: reconnect, and it takes two minutes.
 *
 * The cursor is left behind for a related but separate reason, and is not mirrored into the vault
 * either: a cursor is a claim about what has already been fetched, and the safe direction for it to
 * be wrong in is "fetch too much". A restored connection therefore syncs from the beginning once and
 * the repository de-duplicates what comes back.
 *
 * ## Why the database is copied rather than exported
 *
 * The same reason Maintenance gives. What this app holds is, for the older years, the only copy —
 * providers hand over a rolling window and a bank's own app shows less than this one keeps — so the
 * backup should be the bytes, not a re-serialisation that a future schema change could quietly
 * narrow. A WAL checkpoint runs first so the copied file is the whole database and not a stale main
 * file beside a log holding this morning's refresh.
 *
 * Restore is a whole-file swap, so a Finance restart is expected afterwards — the sandbox surfaces
 * that.
 *
 * The one thing here that can arrive *stale* is the LifeOps task id each bill carries: restore
 * Finance without restoring LifeOps and those ids name tasks that no longer exist. Nothing here
 * compensates for that, deliberately — the next publishing round reads the week, finds them gone,
 * and drops the links. A seam that reconciles doesn't need its backup to be clever.
 */
class FinanceBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.FINANCE
    override val displayName = "Finance"

    override val dataVersion = FINANCE_DB_VERSION

    override fun backup(sink: BackupSink) {
        runCatching {
            FinanceDatabase.getInstance(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }

        financePrefFiles().forEach { file ->
            sink.entry("$PREFS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        val prefsDir = sharedPrefsDir().apply { mkdirs() }
        source.list().filter { it.startsWith(PREFS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(PREFS_PREFIX)
            // Defensive, and doubly so here: only ever write Finance-owned pref files, which by the
            // naming rule above can never include the encrypted credential store.
            if (name.startsWith(PREFS_NAME_PREFIX)) {
                source.open(rel)?.use { input -> File(prefsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

        source.open(DB_ENTRY)?.use { input ->
            FinanceDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun financePrefFiles(): List<File> =
        sharedPrefsDir()
            .listFiles { f -> f.isFile && f.name.startsWith(PREFS_NAME_PREFIX) && f.name.endsWith(".xml") }
            ?.toList().orEmpty()

    companion object {
        private const val DB_NAME = FinanceDatabase.DB_NAME
        private const val DB_ENTRY = "finance.db"
        private const val PREFS_PREFIX = "shared_prefs/"

        /**
         * The prefix that decides what travels.
         *
         * `secure_finance_access` does not start with it, which is the whole mechanism keeping
         * tokens out of the archive. Anything added to this app that holds a secret must be named
         * so that it also fails this test.
         */
        private const val PREFS_NAME_PREFIX = "finance"
    }
}
