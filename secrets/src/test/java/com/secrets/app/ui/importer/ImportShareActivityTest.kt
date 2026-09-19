package com.secrets.app.ui.importer

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the share sheet is allowed to hand this app.
 *
 * This is the one component in the module any installed app can start, so what it accepts is not a
 * detail of a screen — it is the surface. The tests are about the refusals: an intent that is not a
 * share, and a share that carries *text* rather than a file. The second is the one worth stating,
 * because it is the shape a mistake takes: an app offering to send a password list as text would be
 * an app putting passwords through the clipboard and the notification log, and answering it with an
 * import screen would be accepting that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportShareActivityTest {

    private val file: Uri = Uri.parse("content://downloads/public_downloads/42")

    @Test
    fun `a shared file is the file`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, file)

        assertEquals(file, ImportShareActivity.sharedFile(intent))
    }

    @Test
    fun `a share with no file in it is nothing`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "bank.example,me,hunter2")

        assertNull(ImportShareActivity.sharedFile(intent))
    }

    @Test
    fun `an intent that is not a share is nothing, whatever it carries`() {
        val intent = Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, file)

        assertNull(ImportShareActivity.sharedFile(intent))
    }

    @Test
    fun `no intent at all is nothing`() {
        assertNull(ImportShareActivity.sharedFile(null))
    }
}
