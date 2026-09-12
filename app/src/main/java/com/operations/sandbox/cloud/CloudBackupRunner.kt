package com.operations.sandbox.cloud

import android.content.Context
import com.operations.backupkit.cloud.AzureBlobTarget
import com.operations.backupkit.cloud.AzureSas
import com.operations.backupkit.cloud.CloudBackupNaming
import com.operations.backupkit.cloud.CloudBackupRetention
import com.operations.backupkit.cloud.TargetCheck
import com.operations.sandbox.BackupCenter
import com.operations.sandbox.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One cloud backup, start to finish: write the archive, send it, prune what has rolled off, and
 * write down what happened.
 *
 * Shared by the two things that can start one — the periodic worker and the "Back up now" button —
 * so a scheduled archive and a hand-started one are the same archive, produced the same way, and a
 * household testing their settings is testing what will actually run at two in the morning.
 *
 * ## Why it stages to a file
 *
 * The obvious implementation streams [BackupCenter]'s zip straight into the HTTP request and never
 * touches the disk. It cannot be done: blob storage wants a content length up front (and a phone
 * cannot hold a suite-sized archive in memory to find out what it is), and a request body that
 * fails halfway cannot be rewound and retried. So the archive is written to the cache, uploaded,
 * and deleted — in a `finally`, because a failed upload that leaves a copy of the household's
 * entire data set in the cache is its own small disaster.
 */
class CloudBackupRunner(
    context: Context,
    private val prefs: CloudBackupPrefs = CloudBackupPrefs(context),
    private val center: BackupCenter = BackupCenter(context, BuildConfig.VERSION_NAME),
    private val store: AzureBlobStore = AzureBlobStore()
) {

    private val appContext = context.applicationContext

    /** What a run ended as. [transient] is what decides whether the worker asks to be run again. */
    sealed interface Outcome {
        data class Uploaded(val blobName: String, val bytes: Long, val pruned: Int) : Outcome
        data class Failed(val message: String, val transient: Boolean) : Outcome
        /** Nothing was owed, or nothing could be sent — never an error, never a retry. */
        data class Skipped(val reason: String) : Outcome
    }

    /**
     * Take an archive and send it.
     *
     * [now] is passed in rather than read here so the name, the "last run" stamp and the schedule
     * all agree about what time it is — a run that straddles midnight would otherwise be able to
     * name itself one day and record itself on another.
     */
    suspend fun run(now: Long = System.currentTimeMillis()): Outcome = withContext(Dispatchers.IO) {
        val target = when (val check = prefs.target()) {
            is TargetCheck.Ready -> check.target
            is TargetCheck.Incomplete -> return@withContext record(now, Outcome.Failed(check.problem, transient = false))
        }
        // An expired signature is worth its own sentence: Azure would answer 403, which is also
        // what a wrong container and a missing permission look like, and sending somebody to check
        // the wrong one of the three costs an evening.
        if (AzureSas.isExpired(target.sasToken, now)) {
            val expired = AzureSas.expiresAt(target.sasToken)?.let { " on ${DATE.format(Date(it))}" }.orEmpty()
            return@withContext record(
                now,
                Outcome.Failed("The shared access signature expired$expired — paste a new one.", transient = false)
            )
        }
        val selected = prefs.selectedApps.intersect(center.contributors.map { it.appId }.toSet())
        if (selected.isEmpty()) {
            return@withContext record(now, Outcome.Skipped("No apps are selected for the cloud backup."))
        }

        val blobName = CloudBackupNaming.blobName(now)
        val staging = File(appContext.cacheDir, STAGING_DIR).apply { mkdirs() }
        // Anything else in here is a previous run that died before its `finally` — a power loss, a
        // process kill. Clearing it costs nothing and bounds the cache at one archive.
        staging.listFiles()?.forEach { it.delete() }
        val file = File(staging, blobName)
        try {
            val archived = runCatching {
                file.outputStream().use { out -> center.backup(selected, out) }
                file.length()
            }.getOrElse { error ->
                // A cancelled job (the constraints lapsed, the screen went away) is not a failed
                // backup, and recording it as one would leave a household reading "couldn't write
                // the archive" about a run nothing was wrong with.
                if (error is CancellationException) throw error
                return@withContext record(
                    now,
                    Outcome.Failed("Couldn't write the archive: ${error.message ?: error.javaClass.simpleName}", transient = false)
                )
            }

            val upload = store.upload(target, blobName, file)
            if (!upload.succeeded) {
                return@withContext record(now, Outcome.Failed(upload.describe(), upload.transient))
            }
            val pruned = prune(target)
            record(now, Outcome.Uploaded(blobName, archived, pruned))
        } finally {
            file.delete()
        }
    }

    /**
     * Delete the archives that have rolled off the keep-count.
     *
     * Best effort, and never the reason a run is reported as failed: the archive is already safely
     * in the container by the time this happens, and "the backup failed" is the wrong thing to tell
     * somebody whose backup succeeded and whose housekeeping didn't. A signature without list or
     * delete permission simply prunes nothing, which is why those two permissions are optional in
     * the setup instructions and this checks for them rather than trying and reporting a 403.
     */
    private suspend fun prune(target: AzureBlobTarget): Int {
        val keep = prefs.keep
        if (keep <= CloudBackupRetention.KEEP_EVERYTHING) return 0
        if (!AzureSas.canList(target.sasToken) || !AzureSas.canDelete(target.sasToken)) return 0
        val listing = store.list(target)
        if (!listing.call.succeeded) return 0
        var deleted = 0
        for (blob in CloudBackupRetention.expired(listing.names, keep)) {
            if (store.delete(target, blob).succeeded) deleted++
        }
        return deleted
    }

    /**
     * Write the outcome down before returning it.
     *
     * Every path through [run] goes through here, including the failures, and that is the point:
     * `lastRunAt` is what the schedule reads, so a run that failed still counts as an attempt and
     * the phone does not spend the night retrying a wrong container name as fast as WorkManager
     * will let it. `lastSuccessAt` is the one that only moves when an archive actually landed, and
     * it is what the settings screen shows when it says how old the newest backup is.
     */
    private fun record(now: Long, outcome: Outcome): Outcome {
        prefs.lastRunAt = now
        prefs.lastStatus = when (outcome) {
            is Outcome.Uploaded -> buildString {
                append("Uploaded ${outcome.blobName} (${size(outcome.bytes)})")
                if (outcome.pruned > 0) append(", removed ${outcome.pruned} older archive(s)")
                append(".")
            }
            is Outcome.Failed -> outcome.message
            is Outcome.Skipped -> outcome.reason
        }
        if (outcome is Outcome.Uploaded) {
            prefs.lastSuccessAt = now
            prefs.lastBlobName = outcome.blobName
        }
        return outcome
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.0f kB".format(Locale.US, bytes / 1024.0)
        else -> "$bytes bytes"
    }

    private companion object {
        /** One archive at a time, in the cache, where the OS may reclaim it if the run dies. */
        const val STAGING_DIR = "cloud-backup"

        val DATE = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    }
}
