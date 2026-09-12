package com.operations.backupkit.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a real `List Blobs` answer, and reading a real failure. */
class AzureBlobListingTest {

    private val listing = """
        <?xml version="1.0" encoding="utf-8"?>
        <EnumerationResults ServiceEndpoint="https://household.blob.core.windows.net/" ContainerName="backups">
          <Prefix>nightly/</Prefix>
          <Blobs>
            <Blob>
              <Name>nightly/operations-backup-20260910-020000Z.zip</Name>
              <Properties><Content-Length>48213</Content-Length></Properties>
            </Blob>
            <Blob>
              <Name>nightly/operations-backup-20260911-020000Z.zip</Name>
              <Properties><Content-Length>48999</Content-Length></Properties>
            </Blob>
          </Blobs>
          <NextMarker />
        </EnumerationResults>
    """.trimIndent()

    @Test
    fun `every blob in a page is read, in order`() {
        assertEquals(
            listOf(
                "nightly/operations-backup-20260910-020000Z.zip",
                "nightly/operations-backup-20260911-020000Z.zip"
            ),
            AzureBlobListing.blobNames(listing)
        )
    }

    @Test
    fun `an empty next marker means the listing was complete`() {
        assertNull(AzureBlobListing.nextMarker(listing))
        assertEquals(
            "2!68!MDAwMDI",
            AzureBlobListing.nextMarker(listing.replace("<NextMarker />", "<NextMarker>2!68!MDAwMDI</NextMarker>"))
        )
    }

    @Test
    fun `a folder entry is not a blob and can never be handed to a deletion`() {
        val withFolders = listing.replace(
            "<Blobs>",
            "<Blobs><BlobPrefix><Name>nightly/2025/</Name></BlobPrefix>"
        )
        assertFalse(AzureBlobListing.blobNames(withFolders).any { it == "nightly/2025/" })
        assertEquals(2, AzureBlobListing.blobNames(withFolders).size)
    }

    @Test
    fun `an empty container lists nothing rather than failing`() {
        assertEquals(emptyList<String>(), AzureBlobListing.blobNames("<EnumerationResults><Blobs /></EnumerationResults>"))
        assertEquals(emptyList<String>(), AzureBlobListing.blobNames(""))
    }

    @Test
    fun `azure's error document is read for the code the household will search for`() {
        val error = """
            <?xml version="1.0" encoding="utf-8"?>
            <Error>
              <Code>AuthenticationFailed</Code>
              <Message>Server failed to authenticate the request.
            RequestId:1234
            Time:2026-09-12T02:00:00Z</Message>
            </Error>
        """.trimIndent()
        assertEquals("AuthenticationFailed", AzureBlobListing.errorCode(error))
        assertEquals("Server failed to authenticate the request.", AzureBlobListing.errorMessage(error))
        assertNull(AzureBlobListing.errorCode(null))
        assertNull(AzureBlobListing.errorCode("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `a name with an ampersand in it comes back as it was stored`() {
        val xml = "<Blobs><Blob><Name>rent &amp; bills/backup.zip</Name></Blob></Blobs>"
        assertEquals(listOf("rent & bills/backup.zip"), AzureBlobListing.blobNames(xml))
    }

    @Test
    fun `only a server's own problem is worth retrying`() {
        assertTrue(AzureBlobStatus.isTransient(503))
        assertTrue(AzureBlobStatus.isTransient(429))
        assertTrue(AzureBlobStatus.isTransient(408))
        // No response at all — the phone was offline, or the connection was cut mid-upload.
        assertTrue(AzureBlobStatus.isTransient(0))
        // A credential does not fix itself, and retrying it with backoff forever is how a broken
        // setting becomes a battery complaint.
        assertFalse(AzureBlobStatus.isTransient(403))
        assertFalse(AzureBlobStatus.isTransient(404))
        assertTrue(AzureBlobStatus.isSuccess(201))
        assertFalse(AzureBlobStatus.isSuccess(304))
    }

    @Test
    fun `a 403 says the three things it can actually mean`() {
        val message = AzureBlobStatus.describe(403, "AuthorizationPermissionMismatch")
        assertTrue(message.contains("AuthorizationPermissionMismatch"))
        assertTrue(message.contains("expired"))
        assertTrue(message.contains("another container"))
        assertTrue(AzureBlobStatus.describe(404).contains("No such container"))
        assertTrue(AzureBlobStatus.describe(0).contains("Couldn't reach"))
    }
}
