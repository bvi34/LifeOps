package com.operations.sandbox.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.operations.sandbox.BuildConfig
import com.operations.sandbox.update.logic.AppVersion
import com.operations.sandbox.update.logic.AvailableRelease
import com.operations.sandbox.update.logic.InstallCompatibility
import com.operations.sandbox.update.logic.ReleaseFeed
import com.operations.sandbox.update.logic.ReleaseLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The side of the updater that talks to the network and to the package installer.
 *
 * The suite is otherwise a device-local thing, and this is the one place that reaches out on the
 * container's own behalf rather than an app's — so it is deliberately small and deliberately
 * *manual*. Nothing here runs on a schedule, nothing installs anything on its own, and no telemetry
 * leaves the phone: it reads one public JSON document, and only when asked. The decision-making —
 * which asset, which version, whether it's newer — lives next door in `logic/`, unit-tested off the
 * device; what's left here is a GET, a file write and an Intent.
 *
 * Sideloading is what makes this necessary at all. There is no Play Store in the loop, so an APK
 * built by GitHub Actions reaches the phone only if something asks GitHub what the newest one is.
 */
class Updater(
    context: Context,
    private val prefs: UpdatePrefs = UpdatePrefs(context),
    private val repo: String = BuildConfig.UPDATE_REPO
) {

    private val appContext = context.applicationContext

    /** What this APK reports itself as — the tag it was built from, or "0.0.0-dev" locally. */
    val installedVersion: AppVersion = AppVersion.parse(BuildConfig.VERSION_NAME)

    /**
     * Ask GitHub for the newest published release, and say what came back.
     *
     * This used to answer `null` for every outcome — up to date, no APK on the release, rate
     * limited, offline, malformed — on the reasoning that the caller could not act differently on
     * any of them. That was wrong twice over: the caller cannot act, but the *user* can, and the
     * screen was left inferring a cause it had no way to know. It inferred "the repository is
     * private, add a token", which is the wrong advice for every case except one.
     *
     * So the outcomes are distinguished here, where the status code is still in hand, and still
     * without throwing: a [ReleaseLookup] is returned for every path including the failures.
     */
    suspend fun fetchLatestRelease(): ReleaseLookup = withContext(Dispatchers.IO) {
        when (val answer = get(ReleaseFeed.latestReleaseUrl(repo))) {
            is Answer.Failed -> ReleaseLookup.Unreachable(answer.detail)
            is Answer.Received -> when {
                answer.status in 200..299 -> ReleaseFeed.readLatestRelease(answer.body)
                // Rate limiting arrives as a 403 (or 429) with the remaining quota at zero. Without
                // that header check it would be reported as an access problem and send the user off
                // to mint a token that changes nothing.
                answer.isRateLimited -> ReleaseLookup.RateLimited
                // A 404 here is genuinely ambiguous: a private repository is invisible to an
                // anonymous request, and a repository that has simply never published a release
                // answers exactly the same way. One extra request settles it — if the repository
                // itself reads back, the releases are visible and there just aren't any, and no
                // token would have helped.
                answer.status == 404 -> if (repoIsVisible()) {
                    ReleaseLookup.NoReleaseYet
                } else {
                    ReleaseLookup.AccessDenied(answer.status)
                }
                answer.status == 401 || answer.status == 403 -> ReleaseLookup.AccessDenied(answer.status)
                else -> ReleaseLookup.HttpError(answer.status)
            }
        }
    }

    /**
     * Whether the repository's own metadata can be read.
     *
     * Only ever called to interpret a 404 from the releases endpoint, so a failure to answer is
     * treated as "not visible": that path already knows the request it cares about came back 404,
     * and the conservative reading of a second failure is the one that at least mentions access.
     */
    private fun repoIsVisible(): Boolean {
        val answer = get(ReleaseFeed.repoUrl(repo))
        return answer is Answer.Received && answer.status in 200..299
    }

    /** Whether a token has been saved — what decides which of the two download URLs is used. */
    fun hasToken(): Boolean = prefs.token != null

    /**
     * Download [release]'s APK into the app's own cache and hand back the file.
     *
     * Cache, not external storage: the file needs no permission to write there, the OS can reclaim
     * it once the install is done, and a `content://` URI from our own provider is what the
     * installer wants anyway. [onProgress] is called with 0f..1f, or with -1f while the total size
     * is unknown (GitHub always sends Content-Length, but a proxy in between might not).
     */
    suspend fun download(
        release: AvailableRelease,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, UPDATE_DIR).apply { mkdirs() }
        // One file per tag, so a half-finished download of an older release can't be mistaken for
        // this one. Everything else in the directory is stale by definition.
        val target = File(dir, "lifeops-${release.tag}.apk")
        dir.listFiles()?.forEach { if (it != target) it.delete() }
        if (target.exists() && release.apkSizeBytes > 0 && target.length() == release.apkSizeBytes) {
            // Already fully downloaded — an interrupted install, not an interrupted download.
            onProgress(1f)
            return@withContext target
        }
        target.delete()

        val token = prefs.token
        val connection = openDownload(release.downloadUrl(hasToken = token != null), token)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("GitHub answered $code for the download")
            val total = release.apkSizeBytes.takeIf { it > 0 }
                ?: connection.contentLengthLong.takeIf { it > 0 }

            // A partial file must never be left where the "already downloaded" check above can find
            // it, so write beside the target and rename only once the stream has ended cleanly.
            val partial = File(dir, target.name + ".part")
            partial.delete()
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        // Cancelling the screen cancels the download; without this the copy runs to
                        // completion in the background on a connection nobody is waiting for.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(if (total != null) (written.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
            if (total != null && partial.length() != total) {
                partial.delete()
                error("The download ended early (${partial.length()} of $total bytes)")
            }
            if (!partial.renameTo(target)) error("Could not finish writing the download")
            onProgress(1f)
            target
        } catch (cancelled: CancellationException) {
            File(dir, target.name + ".part").delete()
            throw cancelled
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Whether the OS will let this app start an install at all.
     *
     * Since Android 8 "install unknown apps" is granted per-app rather than once for the device, and
     * it cannot be requested with the normal permission dialog — the user has to be sent to a
     * Settings page. Checking first means the update flow can say so *before* a download rather than
     * after it, which is the difference between an explanation and a dead end.
     */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** The Settings page that grants the above, deep-linked to this app. */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Hand [apk] to the system installer.
     *
     * The URI has to come from a FileProvider — a `file://` URI has been a FileUriExposedException
     * since Android 7 — and the read permission has to be granted on the Intent, because the
     * installer is a different process and our provider is not exported.
     */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Whether the installer will accept [apk] as a replacement for this build, or refuse it.
     *
     * Asked *before* the install intent is fired, because the refusal is otherwise a system dialog
     * reading "App not installed as package conflicts with an existing package" — which is Android
     * saying the two builds are signed with different keys, in words that mention neither signing
     * nor what to do next. A developer build signed with the debug keystore and a release signed
     * with the release keystore are exactly that pair, so the first release ever offered to a
     * hand-built install is the one that cannot be installed over the top.
     *
     * Both reads are defensive: an unreadable package or an APK the platform won't parse answers
     * [InstallCompatibility.UNKNOWN], which changes nothing and leaves the installer to decide.
     */
    fun compatibilityOf(apk: File): InstallCompatibility =
        InstallCompatibility.of(installedSigners(), apkSigners(apk))

    /** The certificates this running build is signed with — its rotation lineage included. */
    @Suppress("DEPRECATION")
    private fun installedSigners(): Set<String> = runCatching {
        val packageManager = appContext.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            certificates(
                packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            )
        } else {
            fingerprints(
                packageManager
                    .getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
                    .signatures
            )
        }
    }.getOrDefault(emptySet())

    /** The same, read out of a file that is not installed yet. */
    @Suppress("DEPRECATION")
    private fun apkSigners(apk: File): Set<String> = runCatching {
        val packageManager = appContext.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            certificates(
                packageManager.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            )
        } else {
            fingerprints(
                packageManager
                    .getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                    ?.signatures
            )
        }
    }.getOrDefault(emptySet())

    /**
     * Every certificate a package's signing information knows about, on the Androids that have it.
     *
     * The history counts as much as the current signer: a package whose key has been rotated is
     * still installable by an APK signed with an older certificate in its lineage, so reading only
     * the current one would report a conflict the installer would have accepted. A package signed
     * by several keys at once has no lineage to read, hence the branch.
     */
    private fun certificates(info: PackageInfo?): Set<String> {
        if (info == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val signing = info.signingInfo ?: return emptySet()
        val current: Array<Signature> = signing.apkContentsSigners ?: emptyArray()
        val history: Array<Signature> =
            if (signing.hasMultipleSigners()) emptyArray() else signing.signingCertificateHistory ?: emptyArray()
        return fingerprints(current + history)
    }

    /** SHA-256 of each certificate, which is the form these are compared in. */
    private fun fingerprints(signatures: Array<Signature>?): Set<String> =
        (signatures ?: emptyArray())
            .map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }
            .toSet()

    /** The release's page on GitHub, for reading the notes in a browser. */
    fun releasePageIntent(release: AvailableRelease): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Open the APK stream, following GitHub's redirect by hand.
     *
     * `instanceFollowRedirects` cannot be used here, and the reason is not obvious. The API's asset
     * endpoint answers 302 to a pre-signed URL on a storage host, and that URL carries its own
     * credential in the query string. HttpURLConnection copies the original request's headers onto
     * the redirected request — including `Authorization` — and the storage host rejects a request
     * bearing two credentials outright ("only one auth mechanism allowed"). The download then fails
     * with a 400 that has nothing to do with the token being wrong, which is a genuinely
     * hard-to-diagnose failure on a phone.
     *
     * So: follow redirects manually, and carry the Authorization header only while still talking to
     * the host it was issued for.
     */
    private fun openDownload(url: String, token: String?): HttpURLConnection {
        var current = URL(url)
        val authHost = if (token != null) current.host else null
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                if (token != null && current.host == authHost) {
                    // The asset endpoint hands back the asset's *JSON metadata* unless asked for
                    // bytes — a 500-byte "APK" the installer rejects with a useless error.
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                instanceFollowRedirects = false
            }
            val code = connection.responseCode
            if (code !in 300..399) {
                if (code !in 200..299) {
                    connection.disconnect()
                    error("GitHub answered $code for the download")
                }
                return connection
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) error("GitHub redirected the download to nowhere")
            // Resolved against the current URL, so a relative Location works too.
            current = URL(current, location)
        }
        error("The download redirected too many times")
    }

    /**
     * What one API request came back as.
     *
     * [Failed] is "never got an answer" — no network, DNS, timeout, TLS — which is a different
     * thing from an answer that says no, and the two lead to different sentences on screen.
     */
    private sealed interface Answer {
        data class Received(val status: Int, val body: String, val rateLimitRemaining: Int?) : Answer {
            val isRateLimited: Boolean
                get() = (status == 403 || status == 429) && rateLimitRemaining == 0
        }

        data class Failed(val detail: String?) : Answer
    }

    /**
     * One GET against the API, returning the status rather than throwing on it.
     *
     * The error body has to be read from `errorStream`, not `inputStream` — reading the latter on a
     * 4xx throws, which is how the status code used to get lost on the way up.
     */
    private fun get(url: String): Answer {
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // GitHub rejects requests with no User-Agent outright, and the Accept header pins
                // the API's response shape so a future default can't quietly change the JSON.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                // Needed only while the repository is private, where an anonymous request is a 404.
                prefs.token?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
        }.getOrElse { return Answer.Failed(it.message) }

        try {
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val remaining = connection.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
            return Answer.Received(status, body, remaining)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Answer.Failed(failure.message)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val UPDATE_DIR = "updates"
        const val USER_AGENT = "OperationsSandbox-Updater"
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}
