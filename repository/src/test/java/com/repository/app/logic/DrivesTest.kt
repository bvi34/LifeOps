package com.repository.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which drive a file came from — the one question this app asks about a URI, and the only one it can
 * answer without opening anything.
 *
 * The tests are all about the same distinction: the chip somebody pressed is a *starting point*, and
 * the authority on the URI is what actually happened. An app that conflated the two would tell a
 * household its statement came from OneDrive because that is where they meant to look.
 */
class DrivesTest {

    @Test
    fun `a drive is recognised by the authority on the URI, not by what was asked for`() {
        assertEquals(
            Drive.GOOGLE_DRIVE,
            Drives.of("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D42")
        )
        assertEquals(
            Drive.ONEDRIVE,
            Drives.of("content://com.microsoft.skydrive.content.StorageAccessProvider/document/abc")
        )
        assertEquals(
            Drive.DEVICE,
            Drives.of("content://com.android.providers.downloads.documents/document/17")
        )
    }

    @Test
    fun `an old authority is still that drive, because a phone that has not updated still has one`() {
        assertEquals(
            Drive.GOOGLE_DRIVE,
            Drives.of("content://com.google.android.apps.docs.storage.legacy/document/9")
        )
        assertEquals(
            Drive.ONEDRIVE,
            Drives.of("content://com.microsoft.skydrive.content.external/document/9")
        )
    }

    @Test
    fun `a provider nobody here has named is not a failure - it is just unnamed`() {
        // Box, Nextcloud, a file manager's own provider: the file imports exactly the same way, and
        // the shelf says where it came from in the provider's own terms rather than pretending.
        assertNull(Drives.of("content://com.box.android.documents/document/4"))
        assertEquals("com.box.android.documents", Drives.label("content://com.box.android.documents/document/4"))
        assertEquals("Google Drive", Drives.label("content://com.google.android.apps.docs.storage/document/4"))
    }

    @Test
    fun `an authority is read out of a URI without android to read it with`() {
        assertEquals(
            "com.android.externalstorage.documents",
            Drives.authorityOf("content://com.android.externalstorage.documents/tree/primary%3ADocs")
        )
        // A tree URI, a document URI and one carrying a query all name the same provider.
        assertEquals(
            "provider",
            Drives.authorityOf("content://provider/tree/x/document/y?q=1#frag")
        )
        assertNull(Drives.authorityOf("not-a-uri"))
        assertNull(Drives.authorityOf("content:///document/1"))
        assertNull(Drives.authorityOf(null))
        assertNull(Drives.authorityOf(""))
    }

    @Test
    fun `the phone is offered beside the clouds, because half of these imports start as a download`() {
        assertEquals(
            listOf("Google Drive", "OneDrive", "Dropbox", "This device"),
            Drives.OFFERED.map { it.label }
        )
        assertEquals(listOf(true, true, true, false), Drives.OFFERED.map { it.isCloud })
    }
}
