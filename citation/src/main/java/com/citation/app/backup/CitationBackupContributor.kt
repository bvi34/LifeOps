package com.citation.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.citation.app.audio.SpeechSettingsStore
import com.citation.app.data.db.CITATION_DB_VERSION
import com.citation.app.data.db.CitationDatabase
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import java.io.File

/**
 * Citation's hook into the Operations Sandbox backup. Citation's irreplaceable state is two things:
 * its sovereign Room database (books, chapters, notes, highlights, sync watermarks) and the owned
 * files under `filesDir/sovereign` (imported EPUB/PDF, plus the sync envelope store). The disposable
 * cache (`cacheDir/disposable` — Royal Road chapter bodies) is deliberately *not* backed up: it is
 * refetchable by design, so it stays out of the archive exactly as [com.citation.core.manifest] and
 * the eviction policy intend.
 *
 * The narrator's settings are the third thing, and they are the reason this list is worth keeping
 * honest: `speech_settings.json` sits in `filesDir` *beside* `sovereign/` rather than inside it, so
 * a contributor that carried "the database and the owned files" carried everything except the
 * voice, the speed and the sleep timer — which somebody would discover the first time they pressed
 * play on a restored phone. A file that is one directory away from the sweep is exactly the shape
 * of thing a full backup loses quietly; `BackupCoverageTest` now takes a census of the whole data
 * directory so the next one fails a test instead.
 *
 * Archive layout:
 * ```
 * citation/citation.db            ← WAL-checkpointed, whole-file copy
 * citation/sovereign/…            ← every owned file, paths preserved
 * citation/speech_settings.json   ← the narrator's voice, speed and sleep timer
 * ```
 *
 * Restore is a **whole-file swap** of `citation.db` (not a row merge), so a Citation restart is
 * expected afterwards — the sandbox surfaces that to the user.
 */
class CitationBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.CITATION
    override val displayName = "Citation"

    // The schema the copied citation.db was written at, read from the database declaration itself
    // so the archive manifest (and the sandbox's "Backup format v_" label) can't drift out of step
    // with the schema it describes.
    override val dataVersion = CITATION_DB_VERSION

    override fun backup(sink: BackupSink) {
        // Fold the WAL into the main db so the file copy is current and self-contained.
        runCatching {
            CitationDatabase.get(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }

        val sovereign = sovereignDir()
        if (sovereign.isDirectory) {
            sovereign.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(sovereign).invariantSeparatorsPath
                sink.entry("$SOVEREIGN_PREFIX$rel").use { out -> file.inputStream().use { it.copyTo(out) } }
            }
        }

        val speech = speechSettingsFile()
        if (speech.isFile) {
            sink.entry(SPEECH_ENTRY).use { out -> speech.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        // Sovereign files first (owned EPUB/PDF + sync envelopes).
        val sovereign = sovereignDir()
        source.list().filter { it.startsWith(SOVEREIGN_PREFIX) }.forEach { rel ->
            val dest = File(sovereign, rel.removePrefix(SOVEREIGN_PREFIX))
            dest.parentFile?.mkdirs()
            source.open(rel)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
        }

        // The narrator's settings, which are a whole file and are replaced by one: the store's own
        // decoding is total, so an older build's file restored onto a newer one keeps every field
        // it has and defaults the rest.
        source.open(SPEECH_ENTRY)?.use { input ->
            val speech = speechSettingsFile()
            speech.parentFile?.mkdirs()
            speech.outputStream().use { input.copyTo(it) }
        }

        // Then swap the database file wholesale. Close the live handle, drop stale WAL/SHM sidecars
        // (which could otherwise shadow the restored file), and copy the archived db in its place.
        source.open(DB_ENTRY)?.use { input ->
            CitationDatabase.closeInstance()
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private fun sovereignDir() = File(context.filesDir, "sovereign")

    /** Named by the store that owns it, so the two cannot drift apart. */
    private fun speechSettingsFile() = File(context.filesDir, SpeechSettingsStore.FILE_NAME)

    companion object {
        private const val DB_NAME = "citation.db"
        private const val DB_ENTRY = "citation.db"
        private const val SOVEREIGN_PREFIX = "sovereign/"
        private const val SPEECH_ENTRY = SpeechSettingsStore.FILE_NAME
    }
}
