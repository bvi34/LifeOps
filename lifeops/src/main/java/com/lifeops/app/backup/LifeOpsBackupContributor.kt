package com.lifeops.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.lifeops.app.data.db.LIFEOPS_DB_VERSION
import com.lifeops.app.data.db.LifeOpsDatabase
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * LifeOps' hook into the Operations Sandbox backup. It backs up LifeOps **whole**: the entire
 * `lifeops.db` (every table — tasks, aspects, weeks, game resources and their ledger, counters and
 * events, runbooks, templates, food log, growth snapshots, wellness, …) plus LifeOps' own
 * SharedPreferences (theme, reminders, reading rewards, onboarding, the custom palette).
 *
 * This is a deliberate move away from the older per-table JSON snapshot (`BackupRepository`), which
 * silently omitted whole tables — game resources, counters, runbooks, templates, food log, game
 * scores/unlocks, weather locations — so a "full backup" wasn't actually full. A whole-file copy is
 * complete *by construction* and stays complete as the schema grows. The in-app JSON export
 * (Settings → Backup) still exists for its own uses; it just isn't what the sandbox relies on.
 *
 * Because LifeOps and Citation share one process/package now, `shared_prefs/` holds every hosted
 * app's prefs together, so this contributor is careful to touch only files named `lifeops_*` — it
 * never reads or writes Citation's (or the sandbox's own) preferences.
 *
 * Restore is a whole-file swap of `lifeops.db` (not a row merge), so a LifeOps restart is expected
 * afterwards — the sandbox surfaces that.
 */
class LifeOpsBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.LIFEOPS
    override val displayName = "LifeOps"

    // The schema the copied lifeops.db was written at, read from the database declaration itself so
    // it can't drift: this said 44 while the schema had already reached 51.
    override val dataVersion = LIFEOPS_DB_VERSION

    override fun backup(sink: BackupSink) {
        // Fold the WAL into the main db so the file copy is current and self-contained.
        runCatching {
            LifeOpsDatabase.getInstance(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }

        // LifeOps' own preferences only (lifeops_prefs.xml, lifeops_settings.xml) — not Citation's.
        lifeOpsPrefFiles().forEach { file ->
            sink.entry("$PREFS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        // Preferences first (settings, palette, onboarding).
        val prefsDir = sharedPrefsDir().apply { mkdirs() }
        source.list().filter { it.startsWith(PREFS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(PREFS_PREFIX)
            // Defensive: only ever write LifeOps-owned pref files.
            if (name.startsWith("lifeops")) {
                source.open(rel)?.use { input -> File(prefsDir, name).outputStream().use { input.copyTo(it) } }
            }
        }

        // Then swap the database file wholesale. Close the live handle, drop stale WAL/SHM sidecars
        // (which could otherwise shadow the restored file), and copy the archived db in its place.
        source.open(DB_ENTRY)?.use { input ->
            LifeOpsDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    /** LifeOps' own preference XML files, isolated from the other hosted apps by name prefix. */
    private fun lifeOpsPrefFiles(): List<File> =
        sharedPrefsDir().listFiles { f -> f.isFile && f.name.startsWith("lifeops") && f.name.endsWith(".xml") }
            ?.toList().orEmpty()

    companion object {
        private const val DB_NAME = "lifeops.db"
        private const val DB_ENTRY = "lifeops.db"
        private const val PREFS_PREFIX = "shared_prefs/"
    }
}
