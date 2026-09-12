package com.operations.sandbox.update.logic

import org.json.JSONObject

/**
 * One published release, as much of it as the updater cares about.
 *
 * Two URLs for the same file, because a private repository and a public one are downloaded
 * differently and this app has to work either way:
 *
 *  - [apkBrowserUrl] is the plain public download. It needs no headers and no token, and it is a
 *    404 on a private repository no matter what credentials are attached, because the release
 *    download host does not take an Authorization header at all.
 *  - [apkApiUrl] is the API's asset endpoint. With `Accept: application/octet-stream` it redirects
 *    to a pre-signed URL that carries its own short-lived credential, which is the only way to pull
 *    an asset out of a private repository.
 *
 * [downloadUrl] picks between them: the API route whenever a token is in hand, the browser route
 * otherwise.
 */
data class AvailableRelease(
    val tag: String,
    val version: AppVersion,
    val notes: String,
    val apkBrowserUrl: String,
    val apkApiUrl: String,
    /** Bytes, as GitHub reports the asset; 0 when unknown. Shown so a 90 MB download isn't a surprise. */
    val apkSizeBytes: Long,
    /** The release's page, for the "what changed" link out to a browser. */
    val htmlUrl: String
) {
    fun downloadUrl(hasToken: Boolean): String =
        if (hasToken && apkApiUrl.isNotBlank()) apkApiUrl else apkBrowserUrl
}

/**
 * Why a check for updates ended the way it did.
 *
 * This type exists because the first version of the updater collapsed every outcome into a single
 * `null`, and the screen then had to *guess* what had happened. It guessed "the repository must be
 * private, add a token" — which was wrong, and unactionably so, in the one case that actually
 * happens most: GitHub was read perfectly well, and the newest release simply has no APK attached
 * to it because the release workflow never ran for that tag. No token on earth fixes that, so
 * asking for one sends the user off to mint credentials for a problem that lives in CI.
 *
 * Each case below is a *different sentence* on the screen, and several of them are not errors at
 * all. Distinguishing them is the whole point; the two that mean "there is nothing to install, and
 * nothing you can do from the phone" must never read as "your credentials are wrong".
 */
sealed interface ReleaseLookup {

    /** A release with an installable APK on it. Still has to be newer — see [ReleaseFeed.isUpgrade]. */
    data class Found(val release: AvailableRelease) : ReleaseLookup

    /** GitHub answered, the repository has never published a release the phone could install. */
    data object NoReleaseYet : ReleaseLookup

    /**
     * GitHub answered, the newest release exists, and it has no APK on it.
     *
     * The normal cause is a release made by hand in the browser, or a release workflow that failed
     * before it uploaded. Actionable, but not on the phone.
     */
    data class NoApkAttached(val tag: String) : ReleaseLookup

    /** The newest release's tag isn't a version this app can compare itself against. */
    data class UnreadableTag(val tag: String) : ReleaseLookup

    /** GitHub answered with something that isn't a release document at all. */
    data object Unreadable : ReleaseLookup

    /**
     * 401/403/404 — the releases are not visible to this request.
     *
     * This, and only this, is the case a token fixes.
     */
    data class AccessDenied(val status: Int) : ReleaseLookup

    /** Too many requests from this IP or token. Fixes itself; the user need only wait. */
    data object RateLimited : ReleaseLookup

    /** GitHub answered, badly — a 5xx, or any status not covered above. */
    data class HttpError(val status: Int) : ReleaseLookup

    /** The request never got an answer: no network, DNS, timeout, TLS. */
    data class Unreachable(val detail: String?) : ReleaseLookup
}

/**
 * Turning GitHub's `/releases/latest` response into a [ReleaseLookup].
 *
 * Kept apart from the HTTP that fetches it so the interesting half — which asset counts as the APK,
 * what happens when a release was published with none, how a tag becomes a comparable version — is
 * ordinary JVM code with unit tests, and the networking layer stays a thin call with nothing to get
 * wrong in it.
 */
object ReleaseFeed {

    /** The asset the release workflow uploads. Preferred over any other APK on the release. */
    const val PRIMARY_ASSET_NAME = "release.apk"

    fun latestReleaseUrl(repo: String): String = "https://api.github.com/repos/$repo/releases/latest"

    /** Whether the repository itself can be read — the probe that disambiguates a 404. */
    fun repoUrl(repo: String): String = "https://api.github.com/repos/$repo"

    /**
     * Read the release document, saying precisely what was found.
     *
     * Note the deliberate ordering: the tag is parsed *before* the assets are searched, so a
     * release tagged `nightly` reports [ReleaseLookup.UnreadableTag] rather than being lumped in
     * with a release that has no APK. They have different fixes.
     */
    fun readLatestRelease(json: String): ReleaseLookup {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return ReleaseLookup.Unreadable

        val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return ReleaseLookup.Unreadable

        // `releases/latest` already excludes drafts and pre-releases, but a caller pointed at a
        // different endpoint could hand us one, and shipping a draft to a phone would be a bug.
        // Nothing installable has been published, which is what NoReleaseYet says.
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            return ReleaseLookup.NoReleaseYet
        }

        val version = AppVersion.parse(tag)
        if (version == AppVersion.UNKNOWN) return ReleaseLookup.UnreadableTag(tag)

        val assets = root.optJSONArray("assets") ?: return ReleaseLookup.NoApkAttached(tag)
        var fallback: JSONObject? = null
        var chosen: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.equals(PRIMARY_ASSET_NAME, ignoreCase = true)) {
                chosen = asset
                break
            }
            // Any other APK will do if the expected name isn't there — an older release, or one
            // built before the asset was named. First one wins, since order is publish order.
            if (fallback == null) fallback = asset
        }
        val asset = chosen ?: fallback ?: return ReleaseLookup.NoApkAttached(tag)
        val browserUrl = asset.optString("browser_download_url")
        val apiUrl = asset.optString("url")
        // An APK with no way to fetch it is, to the phone, the same as no APK.
        if (browserUrl.isBlank() && apiUrl.isBlank()) return ReleaseLookup.NoApkAttached(tag)

        return ReleaseLookup.Found(
            AvailableRelease(
                tag = tag,
                version = version,
                notes = root.optString("body").trim(),
                apkBrowserUrl = browserUrl,
                apkApiUrl = apiUrl,
                apkSizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L),
                htmlUrl = root.optString("html_url")
            )
        )
    }

    /** The release, or null if there isn't an installable one — [readLatestRelease] without the reason. */
    fun parseLatestRelease(json: String): AvailableRelease? =
        (readLatestRelease(json) as? ReleaseLookup.Found)?.release

    /**
     * Whether [release] is worth offering over what is running.
     *
     * Strictly newer, so re-installing the same version is never suggested, and a *downgrade* is
     * never suggested either — Android would refuse to install it over the top anyway, and an
     * update prompt that leads to a failed install is worse than no prompt.
     */
    fun isUpgrade(installed: AppVersion, release: AvailableRelease): Boolean =
        release.version > installed
}
