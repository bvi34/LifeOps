package com.operations.sandbox.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.operations.sandbox.update.SelfInstaller
import com.operations.sandbox.update.UpdatePrefs
import com.operations.sandbox.update.Updater
import com.operations.sandbox.update.logic.AvailableRelease
import com.operations.sandbox.update.logic.InstallCompatibility
import com.operations.sandbox.update.logic.InstallOutcome
import com.operations.sandbox.update.logic.ReleaseFeed
import com.operations.sandbox.update.logic.ReleaseLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the app stands relative to the newest published release.
 *
 * Modelled as one closed set of states rather than a handful of booleans because the screen has to
 * be able to draw *exactly one* thing at a time, and "checking" and "an update is ready" being
 * separately-settable flags is how a UI ends up showing both at once.
 */
sealed interface UpdateState {
    /** Nothing asked for yet — the state the app launches in. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Asked, answered, and this build is the newest one published. */
    data object UpToDate : UpdateState

    data class Available(val release: AvailableRelease) : UpdateState

    /** [progress] is 0f..1f, or -1f when the server didn't say how big the file is. */
    data class Downloading(val release: AvailableRelease, val progress: Float) : UpdateState

    /**
     * The APK is on disk and the installer is one tap away — unless [signingConflict], in which
     * case the installer is a dead end and the screen says so instead of walking into it.
     */
    data class ReadyToInstall(
        val release: AvailableRelease,
        val apk: File,
        val signingConflict: Boolean = false
    ) : UpdateState

    /**
     * The APK has been handed to the package installer and the install is running — waiting on the
     * user's confirmation if Android asked for one. [ready] is where to go back to if it doesn't
     * finish: the download is still on disk.
     */
    data class Installing(val ready: ReadyToInstall) : UpdateState

    /**
     * The check ended without an update to offer, and the reason is worth saying out loud.
     *
     * [title] varies because not every one of these is a failure to *check*: "the newest release
     * has no APK on it" is a perfectly successful check whose answer happens to be that there is
     * nothing installable, and filing that under "Couldn't check" is what sent people looking for
     * a broken token.
     */
    data class Failed(val message: String, val title: String = "Couldn't check") : UpdateState
}

/**
 * The update flow's state, held at the shell level.
 *
 * Hoisted above the screens for the same reason [BackupController] is, and more so: the download is
 * tens of megabytes, and a coroutine scoped to the settings screen would die the moment the user
 * backs out to look at something else while it runs. Owned here, the download survives navigation,
 * and the "ready to install" state is still waiting whenever they come back.
 *
 * The shell asks GitHub at launch, at most once every six hours, and that is the *only* thing it
 * does on its own: a check that finds something puts a line on the home screen and stops there.
 * Downloading is a tap and installing is another, because an install closes the app and that should
 * never happen unbidden. The launch check can be switched off entirely on the Updates
 * tab, after which nothing here touches the network unless a button is pressed.
 */
@Stable
class UpdateController(
    private val context: Context,
    private val updater: Updater,
    private val prefs: UpdatePrefs,
    private val scope: CoroutineScope,
    private val installer: SelfInstaller = SelfInstaller(context)
) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** What this build calls itself, for the "you're running…" line. */
    val installedVersion: String get() = updater.installedVersion.toString()

    /** Set once a check has found something newer — what the home screen's banner keys off. */
    val pendingRelease: AvailableRelease?
        get() = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.ReadyToInstall -> s.release
            is UpdateState.Installing -> s.ready.release
            else -> null
        }

    /**
     * The launch-check switch.
     *
     * Preferences are the durable copy; this mirrors them into Compose state, because a plain read
     * of SharedPreferences is invisible to recomposition and the switch would appear not to move.
     * Writing goes to both, in that order, so a process death between the two loses the redraw
     * rather than the setting.
     */
    private var autoCheckState by mutableStateOf(prefs.autoCheck)
    var autoCheck: Boolean
        get() = autoCheckState
        set(enabled) {
            prefs.autoCheck = enabled
            autoCheckState = enabled
        }

    /**
     * Whether a GitHub token has been saved — not the token itself.
     *
     * The screen needs to know whether it has one so it can say so; it never needs to display it,
     * and a credential echoed back into a text field is a credential shown to whoever is looking
     * over your shoulder. Saving one clears any earlier "couldn't reach GitHub" state, because the
     * missing token is very often exactly what that was.
     */
    var hasToken by mutableStateOf(prefs.token != null)
        private set

    fun saveToken(token: String) {
        prefs.token = token
        hasToken = prefs.token != null
        if (state is UpdateState.Failed) state = UpdateState.Idle
    }

    fun clearToken() {
        prefs.token = null
        hasToken = false
    }

    private var job: Job? = null

    /**
     * The launch check: run one only if the switch is on and the throttle has expired.
     *
     * [silent] is what separates it from the button. A background check that fails — aeroplane
     * mode, GitHub having a bad morning — must leave the app looking exactly as it did, because
     * nobody asked it a question and an error card answering an unasked question is just noise. A
     * check the user pressed for always says what happened.
     */
    fun checkOnLaunch() {
        if (!prefs.isAutoCheckDue()) return
        check(silent = true)
    }

    fun check(silent: Boolean = false) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing) return
        // A download already waiting to be installed outranks any check: re-checking would throw
        // away a finished download to tell the user about the release they already have on disk.
        if (state is UpdateState.ReadyToInstall && silent) return
        job?.cancel()
        job = scope.launch {
            if (!silent) state = UpdateState.Checking
            val lookup = updater.fetchLatestRelease()
            // Only a check that actually reached GitHub resets the throttle, so a week spent
            // offline doesn't burn the interval and then go quiet once the network is back. Note
            // that "reached GitHub" includes every answer it gave, not just the useful ones — a
            // repository whose newest release has no APK would otherwise re-ask on every single
            // launch forever, which is the throttle failing in exactly the case it exists for.
            if (lookup !is ReleaseLookup.Unreachable) prefs.lastCheckedAt = System.currentTimeMillis()
            state = when (lookup) {
                is ReleaseLookup.Found -> when {
                    ReleaseFeed.isUpgrade(updater.installedVersion, lookup.release) ->
                        UpdateState.Available(lookup.release)
                    silent -> UpdateState.Idle
                    else -> UpdateState.UpToDate
                }
                // Everything below is "nothing to offer". A launch check stays silent about all of
                // it; a check the user pressed for says which of them it was.
                else -> if (silent) UpdateState.Idle else explain(lookup)
            }
        }
    }

    /**
     * The sentence for a check that came back with nothing.
     *
     * Every branch says what was actually established and what, if anything, the user can do about
     * it. Only [ReleaseLookup.AccessDenied] mentions a token, because only there is a token the
     * fix; the no-APK cases name the release workflow instead, since that is where the missing file
     * was supposed to come from and no amount of fiddling on the phone will conjure it.
     */
    private fun explain(lookup: ReleaseLookup): UpdateState.Failed = when (lookup) {
        is ReleaseLookup.NoApkAttached -> UpdateState.Failed(
            title = "Nothing to install",
            message = "GitHub's newest release is ${lookup.tag}, but it has no APK attached, so " +
                "there is nothing for the updater to install. A release only carries one when the " +
                "release workflow builds it — which it does for tags shaped like v1.2.3, and not " +
                "for a release published by hand. Re-tag the commit and push the tag."
        )
        ReleaseLookup.NoReleaseYet -> UpdateState.Failed(
            title = "Nothing to install",
            message = "The repository's releases are readable, and it hasn't published one yet. " +
                "Push a tag shaped like v1.2.3 and the release workflow will build the APK."
        )
        is ReleaseLookup.UnreadableTag -> UpdateState.Failed(
            title = "Nothing to install",
            message = "GitHub's newest release is tagged \"${lookup.tag}\", which isn't a version " +
                "this app can compare itself against, so it can't tell whether it is newer. " +
                "Releases have to be tagged v1.2.3."
        )
        is ReleaseLookup.AccessDenied -> UpdateState.Failed(
            title = "Couldn't read the releases",
            message = if (hasToken) {
                "GitHub answered ${lookup.status} — the saved token doesn't have read access to " +
                    "this repository's contents, or it has expired. Replace it below."
            } else {
                "GitHub answered ${lookup.status}, so the releases aren't visible to an anonymous " +
                    "request. If the repository is private, add an access token below."
            }
        )
        ReleaseLookup.RateLimited -> UpdateState.Failed(
            message = "GitHub is rate-limiting this phone. Nothing is wrong — wait a few minutes " +
                "and try again. Saving a token raises the limit considerably."
        )
        is ReleaseLookup.HttpError -> UpdateState.Failed(
            message = "GitHub answered ${lookup.status}. If that persists it is GitHub's end, not " +
                "this phone's — githubstatus.com will say."
        )
        is ReleaseLookup.Unreachable -> UpdateState.Failed(
            message = "Couldn't reach GitHub" + (lookup.detail?.let { " ($it)" } ?: "") +
                ". Check the connection and try again."
        )
        ReleaseLookup.Unreadable -> UpdateState.Failed(
            message = "GitHub answered, but not with a release this app could read. Try again, and " +
                "if it keeps happening the release itself is probably malformed."
        )
        // Not a failure, and never routed here.
        is ReleaseLookup.Found -> UpdateState.Failed(message = "An update is available.")
    }

    /**
     * Fetch the APK, then stop and let the user tap Install.
     *
     * Downloading and installing are kept as two taps on purpose. The install itself throws up a
     * full-screen system dialog, and having that appear on its own — because a download the user
     * had half-forgotten about finished — is startling in a way that a button is not.
     */
    fun download(release: AvailableRelease) {
        if (state is UpdateState.Downloading || state is UpdateState.Installing) return
        job?.cancel()
        job = scope.launch {
            state = UpdateState.Downloading(release, 0f)
            val result = runCatching {
                updater.download(release) { progress ->
                    // Only overwrite our own state: if the user cancelled and started a check in the
                    // meantime, a late progress callback must not drag the UI back to Downloading.
                    val current = state
                    if (current is UpdateState.Downloading && current.release.tag == release.tag) {
                        state = current.copy(progress = progress)
                    }
                }
            }
            state = result.fold(
                onSuccess = { apk ->
                    // Read the APK's signature now rather than at the tap: this is the moment the
                    // file exists, and a card that already knows the install cannot work can offer
                    // the way round it instead of a button that leads to a system refusal.
                    val conflict = withContext(Dispatchers.IO) {
                        updater.compatibilityOf(apk) == InstallCompatibility.SIGNATURE_CONFLICT
                    }
                    UpdateState.ReadyToInstall(release, apk, signingConflict = conflict)
                },
                onFailure = { UpdateState.Failed("Download failed: ${it.message ?: "unknown error"}") }
            )
        }
    }

    /** Abandon an in-flight download and go back to offering it. */
    fun cancelDownload() {
        val current = state
        job?.cancel()
        job = null
        if (current is UpdateState.Downloading) state = UpdateState.Available(current.release)
    }

    /**
     * Whether pressing Install should go straight through, with no confirmation from Android. Read
     * once: the answer changes only when an install replaces this process.
     */
    val installsWithoutPrompt: Boolean by lazy { installer.installsWithoutPrompt() }

    init {
        // What the installer said about the session [install] committed. The receiver publishes
        // into a process-wide flow because it has no reference to this controller.
        scope.launch {
            SelfInstaller.outcomes.collect { outcome ->
                val installing = state as? UpdateState.Installing ?: return@collect
                state = when (outcome) {
                    // Android is showing its confirmation screen; the install is still running.
                    InstallOutcome.AwaitingConfirmation -> installing
                    // The process is about to be replaced. Nothing to draw that will be seen.
                    InstallOutcome.Installed -> installing
                    InstallOutcome.Cancelled -> installing.ready
                    is InstallOutcome.Failed -> UpdateState.Failed(outcome.message, title = outcome.title)
                }
            }
        }
    }

    /**
     * Install the downloaded APK over this app, or send the user to grant this app the right to.
     *
     * Returns false when it did the latter, so the screen can explain what just took them to
     * Settings instead of installing.
     */
    fun install(ready: UpdateState.ReadyToInstall): Boolean {
        if (!updater.canInstallPackages()) {
            context.startActivity(updater.unknownSourcesSettingsIntent())
            return false
        }
        job?.cancel()
        state = UpdateState.Installing(ready)
        job = scope.launch {
            runCatching { installer.install(ready.apk) }.onFailure { failure ->
                if (state is UpdateState.Installing) {
                    state = UpdateState.Failed(
                        title = "Not installed",
                        message = "Couldn't hand the update to Android: ${failure.message ?: "unknown error"}"
                    )
                }
            }
        }
        return true
    }

    /**
     * Copy the downloaded APK into a document the user picked.
     *
     * Needed only for the signature-conflict path, and needed *badly* there: the download lives in
     * this app's own cache, and the fix for a conflict is to uninstall this app — which deletes the
     * cache and the APK inside it. Re-downloading afterwards is not an option either, since a
     * private repository's asset needs the token that went with the app. So the file has to be put
     * somewhere that outlives the uninstall before the uninstall happens.
     */
    fun saveApkTo(uri: Uri?, apk: File) {
        if (uri == null) {
            saveStatus = "Not saved."
            return
        }
        scope.launch {
            saveStatus = "Saving…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("Could not open the destination file")
                    out.use { stream -> apk.inputStream().use { it.copyTo(stream) } }
                }
            }
            saveStatus = result.fold(
                onSuccess = {
                    "Saved. It stays there after Operations Sandbox is uninstalled — open it from " +
                        "Files to install."
                },
                onFailure = { "Couldn't save the APK: ${it.message ?: "unknown error"}" }
            )
        }
    }

    /** What the last [saveApkTo] did, for the line under the button. */
    var saveStatus by mutableStateOf<String?>(null)
        private set

    fun openReleasePage(release: AvailableRelease) {
        context.startActivity(updater.releasePageIntent(release))
    }

    /** Back to the start, so a dismissed error doesn't sit on the screen forever. */
    fun dismiss() {
        job?.cancel()
        job = null
        state = UpdateState.Idle
    }
}

@Composable
fun rememberUpdateController(): UpdateController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        // One prefs instance shared by both: the Updater reads the token for its headers and the
        // controller writes it, and two instances would mean the header still carrying the old one.
        val prefs = UpdatePrefs(context)
        UpdateController(context, Updater(context, prefs), prefs, scope)
    }
}
