package com.operations.sandbox

import android.content.Context
import com.advisor.app.backup.AdvisorBackupContributor
import com.citation.app.backup.CitationBackupContributor
import com.health.app.backup.HealthBackupContributor
import com.lifeops.app.backup.LifeOpsBackupContributor
import com.logistics.app.backup.LogisticsBackupContributor
import com.operations.backupkit.AppId
import com.people.app.backup.PeopleBackupContributor
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupEngine
import com.operations.backupkit.BackupManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The sandbox's Android-side backup driver. It owns the registered [BackupContributor]s (the only
 * place the concrete LifeOps/Citation hooks are named) and adapts the pure-JVM [BackupEngine] to
 * Android streams and a scratch directory. All work runs on IO.
 *
 * Registering a newly-hosted app is a one-line change here plus its contributor and [AppId] entry.
 */
class BackupCenter(context: Context, private val sandboxVersion: String) {

    private val appContext = context.applicationContext

    val contributors: List<BackupContributor> = listOf(
        LifeOpsBackupContributor(appContext),
        CitationBackupContributor(appContext),
        LogisticsBackupContributor(appContext),
        AdvisorBackupContributor(appContext),
        HealthBackupContributor(appContext),
        PeopleBackupContributor(appContext)
    )

    /** Write the [selected] apps into [out] as a single archive. [out] is closed by the engine. */
    suspend fun backup(selected: Set<AppId>, out: OutputStream) = withContext(Dispatchers.IO) {
        BackupEngine.backup(contributors.filter { it.appId in selected }, sandboxVersion, out)
    }

    /** Read an archive's manifest without applying anything, so the UI can show what's inside. */
    suspend fun peek(input: InputStream): BackupManifest? = withContext(Dispatchers.IO) {
        BackupEngine.readManifest(input)
    }

    /**
     * Restore the [selected] apps from [input]. Extraction uses a private scratch dir under the
     * cache which is always cleaned up. Returns the archive's manifest.
     */
    suspend fun restore(selected: Set<AppId>, input: InputStream): BackupManifest = withContext(Dispatchers.IO) {
        val work = File(appContext.cacheDir, "sandbox-restore-${System.currentTimeMillis()}")
        try {
            BackupEngine.restore(contributors.filter { it.appId in selected }, work, input)
        } finally {
            work.deleteRecursively()
        }
    }
}
