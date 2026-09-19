package com.operations.vaultkit

import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Working out what somebody just picked, and turning it into candidates.
 *
 * The household is asked for "the file your old password manager gave you" and hands over whatever
 * is in their Downloads folder. That is the right way round — a picker that demanded they first
 * classify their own export would be a picker most people fail — and it makes the guessing this
 * file's job.
 *
 * Four things arrive here in practice:
 *
 *  - **A `.1pux`**, which is a zip with `export.data` inside it. Everything else in the zip is
 *    attachments, which this vault does not keep (see [OnePasswordExport]).
 *  - **A CSV** from a browser or from 1Password. See [CredentialCsv].
 *  - **The `export.data` itself**, from somebody who unzipped it to look inside.
 *  - **The wrong file.** A photo, a spreadsheet, a zip of holiday pictures, or — the one worth
 *    naming separately — this app's own sealed vault out of a backup archive, which has a home and
 *    it is not this one: merging a vault needs its passphrase and happens in Settings.
 *
 * Every refusal comes back as a sentence rather than a false. "Nothing to import" on a file that is
 * plainly full of passwords is how somebody concludes the feature is broken; "that is a sealed
 * vault — Settings merges those" is how they find the screen they wanted.
 */
object ImportFile {

    /**
     * The largest export this will read, in bytes.
     *
     * Sixteen megabytes is far past a household's passwords — forty thousand logins of CSV — and
     * near enough that a `.1pux` full of scanned documents is refused before it is inflated into
     * memory. It is a memory bound rather than a policy: the whole file is decoded at once because
     * the alternative, streaming, would mean holding a half-parsed password list somewhere.
     */
    const val MAX_BYTES = 16 * 1024 * 1024

    /** What a picked file turned out to be. */
    sealed interface Result {
        data class Understood(val read: VaultImport.Read) : Result

        /** [reason] is shown as written, so it is a sentence about what to do next. */
        data class Rejected(val reason: String) : Result
    }

    fun read(
        bytes: ByteArray,
        fileName: String? = null,
        now: Long = System.currentTimeMillis(),
        newId: () -> String = { UUID.randomUUID().toString() }
    ): Result {
        if (bytes.isEmpty()) return Result.Rejected("That file is empty.")
        if (bytes.size > MAX_BYTES) {
            return Result.Rejected(
                "That file is larger than this can read. An export of passwords is a few hundred " +
                    "kilobytes; something this size has documents in it."
            )
        }

        // Before anything else, because it is the one wrong file that has a right answer elsewhere.
        if (VaultEnvelope.decode(bytes) != null) {
            return Result.Rejected(
                "That is a sealed vault from a backup rather than an export. Restore the archive " +
                    "and Settings will offer to merge it — that needs the passphrase it was sealed " +
                    "with, which this screen does not ask for."
            )
        }

        if (bytes.isZip()) return readZip(bytes, now, newId)

        val text = bytes.asText()
            ?: return Result.Rejected(
                "That file is not text. A password export is a CSV or a .1pux; this looks like " +
                    (fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
                        ?.let { "a .$it file." } ?: "something else.")
            )

        if (text.trimStart().startsWith("{")) {
            val read = OnePasswordExport.read(text, now, newId)
                ?: return Result.Rejected(
                    "That is a JSON file, but not a 1Password export. The one to pick is the " +
                        ".1pux file itself, or the export.data inside it."
                )
            return Result.Understood(read)
        }

        val read = CredentialCsv.read(text, now, newId)
            ?: return Result.Rejected(
                "Nothing in that file looks like a password. A browser's export has a column " +
                    "called password, username or note; this one has none of them."
            )
        return Result.Understood(read)
    }

    /**
     * Pull `export.data` out of a `.1pux`.
     *
     * Entries are read with a ceiling on how much each may inflate to, which is the ordinary
     * defence: a zip's header is a claim by whoever wrote it, and a few kilobytes of it can claim to
     * be several gigabytes of zeroes. The ceiling is [MAX_BYTES], the same bound the whole file gets.
     */
    private fun readZip(bytes: ByteArray, now: Long, newId: () -> String): Result {
        val export = extractExport(bytes)
            ?: return Result.Rejected(
                "That is a zip, but there is no 1Password export inside it. The file to pick is " +
                    "the .1pux itself."
            )

        val read = OnePasswordExport.read(export, now, newId)
            ?: return Result.Rejected("That .1pux has no readable items in it.")
        return Result.Understood(read)
    }

    private fun extractExport(bytes: ByteArray): String? = runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == EXPORT_ENTRY) {
                    return@use zip.readAtMost(MAX_BYTES)
                }
                entry = zip.nextEntry
            }
            null
        }
    }.getOrNull()

    private fun java.io.InputStream.readAtMost(limit: Int): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (out.size() + count > limit) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray().asText()
    }

    private fun ByteArray.isZip(): Boolean =
        size > 4 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte() &&
            this[2] == 3.toByte() && this[3] == 4.toByte()

    /**
     * These bytes as UTF-8 text, or null if they are not text at all.
     *
     * The test is for a NUL byte in the first stretch of the file, which is the cheap and reliable
     * way to tell a document from an image: UTF-8 never contains one and every binary format has
     * one within a few hundred bytes. A UTF-16 export — which a couple of Windows tools still write
     * — is refused by the same test, correctly if bluntly: what it would otherwise become is a
     * header full of NULs and a confident "no password column in that file".
     */
    private fun ByteArray.asText(): String? {
        val sample = minOf(size, SNIFF)
        for (index in 0 until sample) if (this[index] == 0.toByte()) return null
        return runCatching { toString(Charsets.UTF_8) }.getOrNull()
    }

    private const val EXPORT_ENTRY = "export.data"
    private const val BUFFER = 8 * 1024
    private const val SNIFF = 512
}
