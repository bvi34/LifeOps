package com.operations.backupkit.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destination, which is the half of "scheduled backups to Azure" that fails silently when it is
 * wrong: a URL that is almost right gets a 404 at two in the morning and nobody finds out until the
 * day they need the archive.
 */
class AzureBlobTargetTest {

    private val sas = "sv=2022-11-02&ss=b&srt=co&sp=rwdlac&se=2026-12-31T23:59:59Z&sig=abc%2Fdef"

    private fun ready(
        account: String = "household",
        container: String = "backups",
        prefix: String = "",
        token: String = sas
    ): AzureBlobTarget {
        val check = AzureBlobTarget.check(account, container, prefix, token)
        assertTrue("expected a usable target, got $check", check is TargetCheck.Ready)
        return (check as TargetCheck.Ready).target
    }

    @Test
    fun `a blob url is the account, the container, the prefix, the name and the signature`() {
        val target = ready(prefix = "nightly")
        assertEquals(
            "https://household.blob.core.windows.net/backups/nightly/operations-backup-20260912-020000Z.zip" +
                "?$sas",
            target.blobUrl("operations-backup-20260912-020000Z.zip")
        )
    }

    @Test
    fun `a listing asks for the container, scoped to the prefix, and continues from a marker`() {
        val target = ready(prefix = "nightly")
        assertEquals(
            "https://household.blob.core.windows.net/backups?restype=container&comp=list" +
                "&prefix=nightly%2F&$sas",
            target.listUrl()
        )
        assertTrue(target.listUrl("2!68!MDAwMDI").contains("&marker=2%2168%21MDAwMDI&"))
    }

    @Test
    fun `no prefix means no prefix parameter and no stray slash in the name`() {
        val target = ready()
        assertEquals("", target.prefix)
        assertEquals(
            "https://household.blob.core.windows.net/backups/archive.zip?$sas",
            target.blobUrl("archive.zip")
        )
        assertTrue(!target.listUrl().contains("prefix="))
    }

    @Test
    fun `a sovereign cloud differs only in the endpoint`() {
        val check = AzureBlobTarget.check("household", "backups", "", sas, "core.usgovcloudapi.net")
        val target = (check as TargetCheck.Ready).target
        assertEquals("https://household.blob.core.usgovcloudapi.net", target.blobEndpoint)
    }

    @Test
    fun `a token is accepted however it was copied`() {
        assertEquals(sas, AzureBlobTarget.normalizeSas("  ?$sas "))
        assertEquals(sas, AzureBlobTarget.normalizeSas(sas))
        // The whole blob URL, pasted straight out of the portal's "generate SAS" box.
        assertEquals(sas, AzureBlobTarget.normalizeSas("https://household.blob.core.windows.net/backups?$sas"))
    }

    @Test
    fun `a prefix is normalized to a trailing slash and nothing else`() {
        assertEquals("", AzureBlobTarget.normalizePrefix("  "))
        assertEquals("nightly/", AzureBlobTarget.normalizePrefix("/nightly/"))
        assertEquals("phones/pixel/", AzureBlobTarget.normalizePrefix("phones/pixel"))
    }

    @Test
    fun `each half of a half-filled form says what is missing rather than failing later`() {
        assertTrue(AzureBlobTarget.check("", "backups", "", sas) is TargetCheck.Incomplete)
        assertTrue(AzureBlobTarget.check("household", "", "", sas) is TargetCheck.Incomplete)
        assertTrue(AzureBlobTarget.check("household", "backups", "", "") is TargetCheck.Incomplete)
        // A password typed where the SAS goes is not a SAS.
        assertTrue(AzureBlobTarget.check("household", "backups", "", "hunter2") is TargetCheck.Incomplete)
    }

    @Test
    fun `azure's own naming rules are enforced here rather than by a 400 at two in the morning`() {
        assertNotNull(AzureBlobTarget.accountProblem("ab"))                    // too short
        assertNotNull(AzureBlobTarget.accountProblem("my-household"))          // hyphens aren't allowed
        assertNull(AzureBlobTarget.accountProblem("household7"))
        assertNotNull(AzureBlobTarget.containerProblem("-backups"))            // must start alphanumeric
        assertNotNull(AzureBlobTarget.containerProblem("back--ups"))           // no double hyphen
        assertNull(AzureBlobTarget.containerProblem("house-backups"))
        assertNotNull(AzureBlobTarget.prefixProblem("../escape/"))
        assertNotNull(AzureBlobTarget.prefixProblem("two//slashes/"))
        assertNull(AzureBlobTarget.prefixProblem("phones/pixel-8/"))
    }

    @Test
    fun `an account name typed with capitals or spaces is still that account`() {
        val target = ready(account = "  HouseHold ", container = " Backups ")
        assertEquals("household", target.account)
        assertEquals("backups", target.container)
    }

    @Test
    fun `the destination reads as a path on the settings screen`() {
        assertEquals("household / backups / nightly/", ready(prefix = "nightly").describe())
        assertEquals("household / backups", ready().describe())
    }
}
