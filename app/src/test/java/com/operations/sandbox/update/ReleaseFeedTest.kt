package com.operations.sandbox.update

import com.operations.sandbox.update.logic.AppVersion
import com.operations.sandbox.update.logic.ReleaseFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading GitHub's `/releases/latest` answer.
 *
 * Every test here fails *closed* — the expected answer to a malformed, empty or half-published
 * release is null, "nothing to offer", never an exception. The updater runs at launch on data
 * fetched over the network, so a release published by a run that died before it uploaded the APK
 * has to read as "no update", not as a crashed launcher.
 */
class ReleaseFeedTest {

    private fun releaseJson(
        tag: String = "v1.4.2",
        assets: String = """[{"name":"release.apk","size":91234567,"url":"https://api.github.com/repos/bvi34/LifeOps/releases/assets/9001","browser_download_url":"https://github.com/bvi34/LifeOps/releases/download/v1.4.2/release.apk"}]""",
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String = "Fixes the thing."
    ) = """
        {
          "tag_name": "$tag",
          "name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "$body",
          "html_url": "https://github.com/bvi34/LifeOps/releases/tag/$tag",
          "assets": $assets
        }
    """.trimIndent()

    @Test
    fun `reads the tag, notes, size and download url`() {
        val release = ReleaseFeed.parseLatestRelease(releaseJson())
        assertNotNull(release)
        release!!
        assertEquals("v1.4.2", release.tag)
        assertEquals(AppVersion(1, 4, 2), release.version)
        assertEquals("Fixes the thing.", release.notes)
        assertEquals(91234567L, release.apkSizeBytes)
        assertEquals(
            "https://github.com/bvi34/LifeOps/releases/download/v1.4.2/release.apk",
            release.apkBrowserUrl
        )
        assertEquals("https://api.github.com/repos/bvi34/LifeOps/releases/assets/9001", release.apkApiUrl)
        assertEquals("https://github.com/bvi34/LifeOps/releases/tag/v1.4.2", release.htmlUrl)
    }

    @Test
    fun `prefers the workflow's own asset over any other apk on the release`() {
        val assets = """[
            {"name":"debug.apk","size":1,"browser_download_url":"https://example.invalid/debug.apk"},
            {"name":"release.apk","size":2,"browser_download_url":"https://example.invalid/release.apk"}
        ]"""
        val release = ReleaseFeed.parseLatestRelease(releaseJson(assets = assets))
        assertEquals("https://example.invalid/release.apk", release?.apkBrowserUrl)
    }

    @Test
    fun `falls back to another apk when the expected name is absent`() {
        val assets = """[
            {"name":"lifeops-1.4.2.apk","size":7,"browser_download_url":"https://example.invalid/a.apk"}
        ]"""
        val release = ReleaseFeed.parseLatestRelease(releaseJson(assets = assets))
        assertEquals("https://example.invalid/a.apk", release?.apkBrowserUrl)
    }

    @Test
    fun `ignores assets that are not apks`() {
        // A release carrying only a mapping file and a checksum has nothing installable on it.
        val assets = """[
            {"name":"mapping.txt","size":10,"browser_download_url":"https://example.invalid/mapping.txt"},
            {"name":"release.apk.sha256","size":64,"browser_download_url":"https://example.invalid/sum"}
        ]"""
        assertNull(ReleaseFeed.parseLatestRelease(releaseJson(assets = assets)))
    }

    @Test
    fun `a release with no assets offers nothing`() {
        assertNull(ReleaseFeed.parseLatestRelease(releaseJson(assets = "[]")))
    }

    @Test
    fun `drafts and pre-releases are never offered`() {
        assertNull(ReleaseFeed.parseLatestRelease(releaseJson(draft = true)))
        assertNull(ReleaseFeed.parseLatestRelease(releaseJson(prerelease = true)))
    }

    @Test
    fun `an unreadable response offers nothing rather than throwing`() {
        assertNull(ReleaseFeed.parseLatestRelease(""))
        assertNull(ReleaseFeed.parseLatestRelease("not json at all"))
        assertNull(ReleaseFeed.parseLatestRelease("[]"))
        // GitHub's rate-limit body: valid JSON, but nothing this parser needs.
        assertNull(ReleaseFeed.parseLatestRelease("""{"message":"API rate limit exceeded"}"""))
    }

    @Test
    fun `a tag that isn't a version offers nothing`() {
        assertNull(ReleaseFeed.parseLatestRelease(releaseJson(tag = "nightly")))
    }

    @Test
    fun `only a strictly newer release is an upgrade`() {
        val release = ReleaseFeed.parseLatestRelease(releaseJson(tag = "v1.4.2"))!!
        assertTrue(ReleaseFeed.isUpgrade(AppVersion.parse("1.4.1"), release))
        assertTrue(ReleaseFeed.isUpgrade(AppVersion.parse("0.0.0-dev"), release))
        // Same version: re-installing what's already there is never suggested.
        assertFalse(ReleaseFeed.isUpgrade(AppVersion.parse("1.4.2"), release))
        // Newer than the release: a downgrade Android would refuse to install anyway.
        assertFalse(ReleaseFeed.isUpgrade(AppVersion.parse("1.5.0"), release))
    }

    @Test
    fun `a token switches the download to the API asset endpoint`() {
        // The whole reason both URLs are carried. On a private repository the browser URL is a 404
        // that no credential can open, so a token has to redirect the download through the API.
        val release = ReleaseFeed.parseLatestRelease(releaseJson())!!
        assertEquals(
            "https://api.github.com/repos/bvi34/LifeOps/releases/assets/9001",
            release.downloadUrl(hasToken = true)
        )
        assertEquals(
            "https://github.com/bvi34/LifeOps/releases/download/v1.4.2/release.apk",
            release.downloadUrl(hasToken = false)
        )
    }

    @Test
    fun `falls back to the browser url when the api url is missing`() {
        // An older release, or a hand-uploaded asset: no `url` field to switch to.
        val assets = """[
            {"name":"release.apk","size":5,"browser_download_url":"https://example.invalid/r.apk"}
        ]"""
        val release = ReleaseFeed.parseLatestRelease(releaseJson(assets = assets))!!
        assertEquals("https://example.invalid/r.apk", release.downloadUrl(hasToken = true))
    }

    @Test
    fun `an asset with neither url offers nothing`() {
        val assets = """[{"name":"release.apk","size":5}]"""
        assertNull(ReleaseFeed.parseLatestRelease(releaseJson(assets = assets)))
    }

    @Test
    fun `builds the endpoint from the repo`() {
        assertEquals(
            "https://api.github.com/repos/bvi34/LifeOps/releases/latest",
            ReleaseFeed.latestReleaseUrl("bvi34/LifeOps")
        )
    }
}
