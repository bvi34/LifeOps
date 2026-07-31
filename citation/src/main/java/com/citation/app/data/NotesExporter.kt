package com.citation.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a rendered Markdown export to a shareable file and hands it to the system share sheet, so
 * your notes can leave Citation as a real `.md` document (drop it into Obsidian, a repo, anywhere).
 *
 * The Markdown itself is produced by the pure `core` `MarkdownExport`; this object is only the thin
 * Android I/O — file in cache + a `FileProvider` URI + an `ACTION_SEND`, mirroring how LifeOps shares
 * its backup file. The file lives in `cacheDir` (disposable, never the sovereign store); a note's
 * canonical home stays the database.
 */
object NotesExporter {

    /** Write [markdown] to a cached `.md` file and open the share sheet. Returns false on I/O failure. */
    fun share(context: Context, markdown: String, fileName: String = "citation-notes.md"): Boolean =
        try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(markdown)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.citation.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_SUBJECT, "Citation Notes")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Export notes").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
}
