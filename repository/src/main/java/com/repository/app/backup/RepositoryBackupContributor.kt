package com.repository.app.backup

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupContributor
import com.operations.backupkit.BackupSink
import com.operations.backupkit.BackupSource
import com.repository.app.data.db.REPOSITORY_DB_VERSION
import com.repository.app.data.db.RepositoryDatabase
import com.repository.app.data.store.DocumentFiles
import java.io.File

/**
 * Repository's hook into the Operations Sandbox backup: `repository.db` **and the documents
 * themselves**.
 *
 * This is the one contributor in the suite where the files matter more than the database. Every
 * other app's backup carries rows that could, at worst, be typed again. Here the rows are captions:
 * lose the folder and what remains is a list of names of documents nobody has any more. So the
 * files are carried in full, and on restore they are written **before** the rows that name them —
 * the same order Health uses, because a row pointing at a file that has not arrived yet is a
 * document the app claims to have and cannot open.
 *
 * The database is copied as a file rather than exported as JSON, after a WAL checkpoint, for the
 * same reason Maintenance does it: the backup should be the bytes, not a re-serialisation a future
 * schema change could quietly narrow.
 */
class RepositoryBackupContributor(private val context: Context) : BackupContributor {

    override val appId = AppId.REPOSITORY
    override val displayName = "Repository"
    override val dataVersion = REPOSITORY_DB_VERSION

    override fun backup(sink: BackupSink) {
        runCatching {
            RepositoryDatabase.getInstance(context)
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
                .use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(RepositoryDatabase.DB_NAME)
        if (dbFile.exists()) {
            sink.entry(DB_ENTRY).use { out -> dbFile.inputStream().use { it.copyTo(out) } }
        }

        DocumentFiles(context).allFiles().forEach { file ->
            sink.entry("$FILES_PREFIX${file.name}").use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun restore(source: BackupSource) {
        // Files first, deliberately: see the class note.
        val files = DocumentFiles(context)
        source.list().filter { it.startsWith(FILES_PREFIX) }.forEach { entry ->
            val name = entry.removePrefix(FILES_PREFIX)
            val target = files.fileFor(name) ?: return@forEach
            target.parentFile?.mkdirs()
            source.open(entry)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        }

        source.open(DB_ENTRY)?.use { input ->
            RepositoryDatabase.closeInstance()
            val dbFile = context.getDatabasePath(RepositoryDatabase.DB_NAME)
            dbFile.parentFile?.mkdirs()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            dbFile.outputStream().use { input.copyTo(it) }
        }
    }

    private companion object {
        const val DB_ENTRY = "repository.db"
        const val FILES_PREFIX = "documents/"
    }
}
