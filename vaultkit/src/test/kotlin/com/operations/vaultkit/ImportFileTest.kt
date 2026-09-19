package com.operations.vaultkit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when somebody picks the wrong file, which is most of what this code is for.
 *
 * The right file works and there is one test for it. The other seven are the picker's real traffic:
 * a photo, a spreadsheet, an empty file, a zip of something else, and — the one with a right answer
 * somewhere else in the app — this suite's own sealed vault out of a backup archive.
 */
class ImportFileTest {

    private val now = 1_700_000_000_000L

    private fun read(bytes: ByteArray, name: String? = null) =
        ImportFile.read(bytes, name, now) { "id" }

    private fun onepux(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val export = """
        {"accounts":[{"vaults":[{"attrs":{"name":"Personal"},"items":[
          {"item":{"uuid":"a","categoryUuid":"001","state":"active",
            "overview":{"title":"Bank","url":"https://bank.example"},
            "details":{"loginFields":[{"designation":"password","value":"hunter2"}]}}}]}]}]}
    """.trimIndent()

    @Test
    fun `a 1pux is a zip with an export in it`() {
        val result = read(
            onepux(mapOf("export.data" to export, "files/passport.pdf" to "not really a pdf")),
            "1PasswordExport.1pux"
        )

        val read = (result as ImportFile.Result.Understood).read
        assertEquals(VaultImport.Format.ONEPASSWORD_1PUX, read.format)
        assertEquals("hunter2", read.items.single().secret)
    }

    @Test
    fun `the export data on its own is read too`() {
        val result = read(export.toByteArray(), "export.data")

        assertTrue(result is ImportFile.Result.Understood)
    }

    @Test
    fun `a csv is read`() {
        val result = read("name,url,username,password\nBank,https://b.example,me,x\n".toByteArray())

        assertEquals(
            VaultImport.Format.CHROMIUM_CSV,
            (result as ImportFile.Result.Understood).read.format
        )
    }

    @Test
    fun `a sealed vault is sent to the screen that can open one`() {
        val file = VaultEnvelope.create(
            passphrase = "correct horse battery staple".toCharArray(),
            document = VaultJson.encode(VaultDocument.EMPTY),
            iterations = 1000
        )

        val result = read(VaultEnvelope.encode(file), "secrets.vault")

        val reason = (result as ImportFile.Result.Rejected).reason
        assertTrue(reason, reason.contains("Settings"))
        assertTrue(reason, reason.contains("passphrase"))
    }

    @Test
    fun `a zip of something else says what to pick instead`() {
        val result = read(onepux(mapOf("holiday.jpg" to "pretend this is a photo")), "photos.zip")

        assertTrue((result as ImportFile.Result.Rejected).reason.contains(".1pux"))
    }

    @Test
    fun `a binary file is refused by what it is, not by what it is called`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 13)

        val result = read(png, "passwords.csv")

        assertTrue((result as ImportFile.Result.Rejected).reason.contains("not text"))
    }

    @Test
    fun `an empty file is empty`() {
        assertTrue(read(ByteArray(0)) is ImportFile.Result.Rejected)
    }

    @Test
    fun `a spreadsheet of something else is not a password export`() {
        val result = read("date,payee,amount\n2026-01-01,Shop,12.40\n".toByteArray())

        assertTrue((result as ImportFile.Result.Rejected).reason.contains("password"))
    }
}
