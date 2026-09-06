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
 * Turning GitHub's `/releases/latest` response into an [AvailableRelease].
 *
 * Kept apart from the HTTP that fetches it so the interesting half — which asset counts as the APK,
 * what happens when a release was published with none, how a tag becomes a comparable version — is
 * ordinary JVM code with unit tests, and the networking layer stays a thin call with nothing to get
 * wrong in it.
 *
 * Every failure mode here answers `null`, meaning "there is nothing to offer". A release with no APK
 * attached is the normal case for a run that failed halfway, and it must read as "no update", never
 * as an error the user has to dismiss.
 */
object ReleaseFeed {

    /** The asset the release workflow uploads. Preferred over any other APK on the release. */
    const val PRIMARY_ASSET_NAME = "release.apk"

    fun latestReleaseUrl(repo: String): String = "https://api.github.com/repos/$repo/releases/latest"

    fun parseLatestRelease(json: String): AvailableRelease? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        // `releases/latest` already excludes drafts and pre-releases, but a caller pointed at a
        // different endpoint could hand us one, and shipping a draft to a phone would be a bug.
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return null

        val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val version = AppVersion.parse(tag)
        if (version == AppVersion.UNKNOWN) return null

        val assets = root.optJSONArray("assets") ?: return null
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
        val asset = chosen ?: fallback ?: return null
        val browserUrl = asset.optString("browser_download_url")
        val apiUrl = asset.optString("url")
        // One of the two has to be usable; which one is the caller's problem, not the parser's.
        if (browserUrl.isBlank() && apiUrl.isBlank()) return null

        return AvailableRelease(
            tag = tag,
            version = version,
            notes = root.optString("body").trim(),
            apkBrowserUrl = browserUrl,
            apkApiUrl = apiUrl,
            apkSizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L),
            htmlUrl = root.optString("html_url")
        )
    }

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
