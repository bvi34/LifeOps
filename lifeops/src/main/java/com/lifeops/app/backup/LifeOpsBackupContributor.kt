package com.lifeops.app.backup

import android.content.Context
import com.lifeops.app.LifeOpsApp
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import kotlinx.coroutines.runBlocking

/**
 * LifeOps' hook into the Operations Sandbox backup. It reuses LifeOps' existing lossless JSON
 * snapshot (`BackupRepository`) verbatim — the same bytes the in-app Settings → Backup produces —
 * so there is exactly one backup format to reason about and restore stays a superset-merge into the
 * live database (idempotent upserts), needing no app restart.
 *
 * The whole payload is a single archive entry, `lifeops/data.json`. The custom theme palette rides
 * inside that JSON (as it already does for the in-app backup) and is re-applied on restore.
 */
class LifeOpsBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.LIFEOPS
    override val displayName = "LifeOps"

    // Mirrors BackupData.version in BackupRepository. The JSON itself also carries this, so an older
    // archive still restores; this is the version stamped into the sandbox manifest for display.
    override val dataVersion = 14

    override fun backup(sink: BackupSink) {
        val runtime = LifeOpsApp.get(context)
        val repo = runtime.backupRepository
        val palette = runtime.preferencesRepository.customPalette
        val json = runBlocking { repo.buildBackupJson(palette) }
        sink.entry(DATA_ENTRY).use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }

    override fun restore(source: BackupSource) {
        val json = source.open(DATA_ENTRY)?.use { it.reader(Charsets.UTF_8).readText() } ?: return
        val runtime = LifeOpsApp.get(context)
        runBlocking { runtime.backupRepository.restore(json) }.getOrThrow()
        // Re-apply the theme palette the same way the in-app restore does.
        runtime.backupRepository.extractCustomPalette(json)?.let { palette ->
            runtime.preferencesRepository.customPalette = palette
        }
    }

    companion object {
        private const val DATA_ENTRY = "data.json"
    }
}
