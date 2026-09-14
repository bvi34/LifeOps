package com.operations.sandbox.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.operations.backupkit.AppId
import com.operations.backupkit.cloud.AzureSas
import com.operations.backupkit.cloud.CloudBackupFrequency
import com.operations.backupkit.cloud.CloudBackupRetention
import com.operations.backupkit.cloud.TargetCheck
import com.operations.sandbox.cloud.CloudBackupPrefs
import com.operations.sandbox.cloud.CloudBackupRunner
import com.operations.sandbox.cloud.ScheduledCloudBackupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The scheduled-cloud-backup settings, held above the screen that shows them.
 *
 * Hoisted for the reason [BackupController] is: "Back up now" writes and uploads the household's
 * whole suite, and a coroutine scoped to the settings screen would die the moment somebody backed
 * out to look at something while it ran.
 *
 * Two copies of every setting, as elsewhere in the shell: preferences are the durable one, Compose
 * state is the one the screen can see. Writes go to preferences first and state second, so a
 * process death between them loses a redraw rather than a setting — and every write that changes
 * *when* or *whether* the job runs re-registers it with WorkManager through
 * [ScheduledCloudBackupWorker.sync], which is the only place that knows how the job is enqueued.
 */
@Stable
class CloudBackupController(
    private val context: Context,
    private val prefs: CloudBackupPrefs,
    private val scope: CoroutineScope
) {

    private var enabledState by mutableStateOf(prefs.enabled)
    var enabled: Boolean
        get() = enabledState
        set(value) {
            prefs.enabled = value
            enabledState = value
            // Switching it on with a half-filled form is allowed and does nothing harmful: the job
            // is registered, wakes, finds the destination incomplete and says so on this screen
            // rather than failing silently in the background.
            ScheduledCloudBackupWorker.sync(context)
        }

    private var accountState by mutableStateOf(prefs.account)
    var account: String
        get() = accountState
        set(value) {
            prefs.account = value
            accountState = value
            revalidate()
        }

    private var containerState by mutableStateOf(prefs.container)
    var container: String
        get() = containerState
        set(value) {
            prefs.container = value
            containerState = value
            revalidate()
        }

    private var prefixState by mutableStateOf(prefs.prefix)
    var prefix: String
        get() = prefixState
        set(value) {
            prefs.prefix = value
            prefixState = value
            revalidate()
        }

    /** The signature as typed. Shown masked; never logged, never put in a status message. */
    private var sasState by mutableStateOf(prefs.sasToken.orEmpty())
    var sasToken: String
        get() = sasState
        set(value) {
            prefs.sasToken = value
            // Read back rather than stored as typed: the preference normalizes a token pasted as a
            // whole URL or with its leading `?`, and the field should show what will be sent.
            sasState = prefs.sasToken.orEmpty()
            revalidate()
        }

    private var frequencyState by mutableStateOf(prefs.frequency)
    var frequency: CloudBackupFrequency
        get() = frequencyState
        set(value) {
            prefs.frequency = value
            frequencyState = value
            ScheduledCloudBackupWorker.sync(context)
        }

    private var wifiOnlyState by mutableStateOf(prefs.wifiOnly)
    var wifiOnly: Boolean
        get() = wifiOnlyState
        set(value) {
            prefs.wifiOnly = value
            wifiOnlyState = value
            ScheduledCloudBackupWorker.sync(context)
        }

    /**
     * How many archives to keep. Changing it re-registers nothing: pruning happens inside a run, so
     * a new count applies the next time one happens rather than needing the job rebuilt.
     */
    private var keepState by mutableStateOf(prefs.keep)
    var keep: Int
        get() = keepState
        set(value) {
            prefs.keep = value
            keepState = prefs.keep
        }

    /** True while a hand-started archive is being written or sent. */
    var working by mutableStateOf(false)
        private set

    /** The last thing that happened, whether this session started it or a scheduled run did. */
    var status by mutableStateOf(prefs.lastStatus)
        private set

    /** Which apps a scheduled archive includes — the ticks at the top of the Backups tab. */
    val includedApps: Set<AppId> get() = prefs.selectedApps

    /**
     * The destination, or the first thing wrong with it. Recomputed on every edit rather than held,
     * because a validity flag and an address that can disagree is how a screen ends up showing a
     * green tick over somewhere nothing will ever reach.
     */
    private var check by mutableStateOf(prefs.target())

    /** What is stopping this from working, or null when nothing is. */
    val problem: String? get() = (check as? TargetCheck.Incomplete)?.problem

    /** The destination in one line: `household / backups / nightly/`. */
    val destination: String? get() = (check as? TargetCheck.Ready)?.target?.describe()

    /** What the signature allows and how long for, straight off the token. */
    val signature: String get() = AzureSas.describe(sasToken, System.currentTimeMillis())

    /** Whether the pasted signature has already expired — worth saying loudly rather than in prose. */
    val signatureExpired: Boolean get() = sasToken.isNotBlank() && AzureSas.isExpired(sasToken, System.currentTimeMillis())

    /** Whether retention can work: pruning needs a signature that may list and delete. */
    val canPrune: Boolean get() = AzureSas.canList(sasToken) && AzureSas.canDelete(sasToken)

    /** "2 hours ago", or null if an archive has never landed. */
    val lastSuccess: String? get() = prefs.lastSuccessAt.takeIf { it > 0L }?.let { relative(it) }

    /** When the next scheduled archive is owed, or null when the schedule is off. */
    val nextRun: String? get() = if (!enabled) null else relative(prefs.nextRunAt())

    /**
     * Remember which apps a scheduled archive should include.
     *
     * The Backups tab has one list of ticks and it governs everything on the tab — the local zip,
     * the restore, and this. Keeping a second list for the schedule would mean a household could
     * untick Finance, take a backup without it, and still be uploading it every night.
     */
    fun setIncludedApps(apps: Set<AppId>) {
        if (apps != prefs.selectedApps) prefs.selectedApps = apps
    }

    /** How the included apps read under the switch. */
    fun includedSummary(total: Int): String {
        val included = includedApps.size
        return if (included == total) "every app ticked above" else "$included of $total apps ticked above"
    }

    /** How the retention setting reads. */
    fun keepSummary(): String = CloudBackupRetention.describe(keep)

    /**
     * Take an archive right now, on the same path the scheduled run takes.
     *
     * The point of the button is that it is not a *different* path: a household testing their
     * settings is testing the thing that will run at two in the morning, and a 403 shows up here,
     * in front of somebody, rather than in a log nobody reads.
     */
    fun backUpNow() {
        if (working) return
        scope.launch {
            working = true
            status = "Archiving and uploading…"
            val outcome = runCatching { CloudBackupRunner(context, prefs).run() }
            working = false
            status = outcome.fold(
                onSuccess = { prefs.lastStatus },
                onFailure = { "Backup failed: ${it.message ?: it.javaClass.simpleName}" }
            )
        }
    }

    private fun revalidate() {
        check = prefs.target()
    }

    private fun relative(at: Long): String =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
fun rememberCloudBackupController(): CloudBackupController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(scope) { CloudBackupController(context, CloudBackupPrefs(context), scope) }
}
