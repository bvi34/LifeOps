package com.health.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.health.app.HealthApp
import com.health.app.data.db.HEALTH_DB_VERSION
import com.health.app.data.db.HealthDatabase
import com.health.app.data.prefs.HealthPrefs
import com.health.app.data.store.CardImageStore
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * Health's hook into the Operations Sandbox backup, modeled on LifeOps' and Logistics': it copies
 * the whole `health.db` — every profile, reading, symptom, medicine, dose, illness, care note, and
 * the medicine cabinet with the drug facts cached for it — so a "full backup" is complete by
 * construction and stays complete as the schema grows.
 *
 * Health's preferences go with it, but only Health's: the hosted apps share one process and
 * therefore one `shared_prefs/` directory, so this contributor touches only files named `health_*`,
 * exactly as LifeOps confines itself to `lifeops*`. The settings in there are small but worth
 * carrying — restoring onto a new device and finding it opens on the wrong family member is a poor
 * first impression of a restore — and they also hold Health's **People-seam cursors**. Those matter
 * more than they look: a restore that reset them to zero would re-take every packet still sitting in
 * the peers' envelopes, resurrecting people who had since been archived here because their old
 * packets would arrive looking newer than nothing at all.
 *
 * **Card photographs go with it.** Insurance card images are the one thing Health keeps outside the
 * database — a couple of megabytes each, in `filesDir/insurance-cards`, with only the file name on
 * the row (see [CardImageStore]). A backup that carried the row and not the picture would restore a
 * card that points at nothing, which is a worse outcome than not backing it up at all: the app would
 * look like it had the photo and quietly wouldn't. So the directory is copied entry by entry, and
 * restored the same way.
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

        // The card photographs, which the rows only name. See the note above.
        CardImageStore(context).allFiles().forEach { file ->
            sink.entry("$CARDS_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
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

        // The card photographs, before the database that names them — so that the moment the rows
        // arrive, every file they point at is already on disk. The other order leaves a window in
        // which a card exists and its picture doesn't.
        val cardsDir = File(context.filesDir, CardImageStore.DIR_NAME).apply { mkdirs() }
        source.list().filter { it.startsWith(CARDS_PREFIX) }.forEach { rel ->
            val name = rel.removePrefix(CARDS_PREFIX)
            // Defensive: an archive entry is not allowed to name a path outside the directory.
            if (name.isNotBlank() && !name.contains('/') && !name.contains("..")) {
                source.open(rel)?.use { input ->
                    File(cardsDir, name).outputStream().use { input.copyTo(it) }
                }
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

        // Re-arm every medication reminder against the database that has just arrived.
        //
        // The work queue survived the restore and still refers to the medicines of the database that
        // was replaced — ids that may not exist any more, reminders that were never set on this
        // device, and nothing at all for the reminders that were. Cancelling the lot and rebuilding
        // from the restored rows is the only version of this that can't leave somebody being nudged
        // about a medicine they don't have, or not nudged about one they do.
        runCatching { HealthApp.get(context).rescheduleReminders() }
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

        /** Where the card photographs sit in the archive, mirroring their directory on disk. */
        private const val CARDS_PREFIX = "${CardImageStore.DIR_NAME}/"

        /** Matches [HealthPrefs.FILE_NAME] and anything Health adds later under the same prefix. */
        private const val PREFS_NAME_PREFIX = "health"
    }
}
